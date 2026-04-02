package com.hsbc.pluse.sample.poc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsbc.pluse.observe.EventFlowTracer;
import io.opentelemetry.api.trace.Span;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Strategy B scheduler processor that drains PostgreSQL and completes processing.
 * Runs periodically to pick up pending events and publish to poc-stored-out.
 */
@Component
@ConditionalOnProperty(name = "eventflow.poc.enable-strategy-b", havingValue = "true", matchIfMissing = true)
public class PocEventProcessor {

    private static final Logger log = LoggerFactory.getLogger(PocEventProcessor.class);

    private final EventStoreRepository repository;
    private final EventFlowTracer tracer;
    private final PocMetrics pocMetrics;
    private final PocRuntimeTuning runtimeTuning;
    private final ObjectMapper objectMapper;
    private final KafkaProducer<String, byte[]> kafkaProducer;
    private final ExecutorService processPool;
    private final AtomicLong lastRunAtMs = new AtomicLong(0);
    private final AtomicBoolean processing = new AtomicBoolean(false);

    @org.springframework.beans.factory.annotation.Value("${eventflow.poc.batch-size:500}")
    private int batchSize;

    public PocEventProcessor(EventStoreRepository repository, EventFlowTracer tracer, PocMetrics pocMetrics,
                             PocRuntimeTuning runtimeTuning,
                             @org.springframework.beans.factory.annotation.Value("${eventflow.dispatch.concurrent-max-size:8}") int poolSize) {
        this.repository = repository;
        this.tracer = tracer;
        this.pocMetrics = pocMetrics;
        this.runtimeTuning = runtimeTuning;
        this.objectMapper = new ObjectMapper();
        this.processPool = Executors.newFixedThreadPool(poolSize);

        // Dedicated KafkaProducer for strategy B outbound
        Properties props = new Properties();
        props.put("bootstrap.servers", "localhost:9092");
        props.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        props.put("value.serializer", "org.apache.kafka.common.serialization.ByteArraySerializer");
        props.put("acks", "all");
        this.kafkaProducer = new KafkaProducer<>(props);
    }

    @Scheduled(fixedDelay = 20)
    public void processPending() {
        if (!processing.compareAndSet(false, true)) {
            return;
        }
        try {
            long delayMs = runtimeTuning.getSchedulerDelayMs();
            long now = System.currentTimeMillis();
            long last = lastRunAtMs.get();
            if (now - last < delayMs) {
                return;
            }
            if (!lastRunAtMs.compareAndSet(last, now)) {
                return;
            }

            tracer.tracedInternal("poc.scheduler.tick", () -> {
                Span.current().setAttribute("poc.strategy", "B");
                Span.current().setAttribute("poc.phase", "scheduler");
                Span.current().setAttribute("poc.component", "scheduler");
                Span.current().setAttribute("poc.scheduler.delayMs", delayMs);
                Span.current().setAttribute("poc.scheduler.batchSize", batchSize);

                while (true) {
                    final long fetchStartMs = System.currentTimeMillis();
                    List<EventStoreEntry> pending = tracer.tracedInternal("poc.scheduler.fetchPending", () -> {
                        Span.current().setAttribute("poc.strategy", "B");
                        Span.current().setAttribute("poc.phase", "scheduler");
                        Span.current().setAttribute("poc.component", "scheduler");
                        return repository.findPending(batchSize);
                    });
                    final long fetchDurationMs = System.currentTimeMillis() - fetchStartMs;
                    if (pending.isEmpty()) {
                        return null;
                    }

                    log.debug("Processing {} pending events from PostgreSQL", pending.size());
                    Span.current().setAttribute("poc.scheduler.pendingBatchSize", pending.size());

                    CompletableFuture<String>[] futures = pending.stream().map(entry ->
                        CompletableFuture.supplyAsync(() -> processEntry(entry), processPool)
                    ).toArray(CompletableFuture[]::new);

                    tracer.tracedInternal("poc.scheduler.awaitBatch", () -> {
                        Span.current().setAttribute("poc.strategy", "B");
                        Span.current().setAttribute("poc.phase", "scheduler");
                        Span.current().setAttribute("poc.component", "scheduler");
                        CompletableFuture.allOf(futures).join();
                        return null;
                    });

                    List<String> processedIds = Arrays.stream(futures)
                        .map(future -> future.getNow(null))
                        .filter(Objects::nonNull)
                        .toList();

                    final long markStartMs = System.currentTimeMillis();
                    tracer.tracedInternal("poc.scheduler.markProcessed", () -> {
                        Span.current().setAttribute("poc.strategy", "B");
                        Span.current().setAttribute("poc.phase", "scheduler");
                        Span.current().setAttribute("poc.component", "scheduler");
                        Span.current().setAttribute("poc.scheduler.processedBatchSize", processedIds.size());
                        repository.markProcessedBatch(processedIds);
                        return null;
                    });
                    final long markDurationMs = System.currentTimeMillis() - markStartMs;

                    // Mirror batch-level scheduler/DB timing into each event trace tree so one traceId shows full lifecycle.
                    Set<String> processedIdSet = new HashSet<>(processedIds);
                    for (EventStoreEntry entry : pending) {
                        if (!processedIdSet.contains(entry.id()) || entry.parentTraceId() == null || entry.parentSpanId() == null) {
                            continue;
                        }
                        tracer.tracedWithLink("poc.scheduler.batch.finalize", "t_poc", entry.id(),
                                entry.parentTraceId(), entry.parentSpanId(), () -> {
                            Span.current().setAttribute("poc.strategy", "B");
                            Span.current().setAttribute("poc.phase", "scheduler");
                            Span.current().setAttribute("poc.component", "scheduler");
                            Span.current().setAttribute("poc.scheduler.pendingBatchSize", pending.size());
                            Span.current().setAttribute("poc.scheduler.processedBatchSize", processedIds.size());
                            Span.current().setAttribute("poc.scheduler.fetchPendingDurationMs", fetchDurationMs);
                            Span.current().setAttribute("poc.scheduler.markProcessedDurationMs", markDurationMs);
                            Span.current().setAttribute("poc.scheduler.batchFinalize", true);
                            return null;
                        });
                    }

                    if (processedIds.size() != pending.size()) {
                        log.warn("Batch processing completed with failures: total={}, success={}, failed={}",
                            pending.size(), processedIds.size(), pending.size() - processedIds.size());
                    }
                    if (pending.size() < batchSize) {
                        return null;
                    }

                    log.debug("Batch reached size limit; continue processing next batch immediately");
                }
            });
        } finally {
            processing.set(false);
        }
    }

    private String processEntry(EventStoreEntry entry) {
        try {
            return tracer.tracedWithLink("poc.strategyB.process", "t_poc", entry.id(),
                    entry.parentTraceId(), entry.parentSpanId(), () -> {
                Span.current().setAttribute("poc.strategy", "B");
                Span.current().setAttribute("poc.phase", "process");
                Span.current().setAttribute("poc.component", "scheduler");
                if (entry.parentTraceId() != null) {
                    Span.current().setAttribute("poc.originalTraceId", entry.parentTraceId());
                }

                long storeDelayMs = Duration.between(entry.createdAt(), Instant.now()).toMillis();
                long schedulerDelayMs = runtimeTuning.getSchedulerDelayMs();
                long schedulerExcessDelayMs = Math.max(0, storeDelayMs - schedulerDelayMs);
                Span.current().setAttribute("poc.storeDelayMs", storeDelayMs);
                Span.current().setAttribute("poc.scheduler.queueDelayMs", storeDelayMs);
                Span.current().setAttribute("poc.scheduler.configDelayMs", schedulerDelayMs);
                Span.current().setAttribute("poc.scheduler.excessDelayMs", schedulerExcessDelayMs);
                pocMetrics.recordStoreDelay(storeDelayMs);

                tracer.tracedInternal("poc.business.process", () -> {
                    Span.current().setAttribute("poc.component", "business");
                    simulateBusinessLatency();
                    return null;
                });

                tracer.tracedInternal("poc.outbound.publish", () -> {
                    Span.current().setAttribute("poc.component", "outbound");
                    try {
                        byte[] payload = objectMapper.writeValueAsBytes(Map.of(
                            "strategy", "B",
                            "eventId", entry.id(),
                            "storeDelayMs", storeDelayMs,
                            "originalTraceId", entry.parentTraceId() != null ? entry.parentTraceId() : ""
                        ));
                        kafkaProducer.send(new ProducerRecord<>("poc-stored-out", entry.id(), payload));
                    } catch (Exception e) {
                        log.error("Failed to publish strategy B result for event {}", entry.id(), e);
                    }
                    return null;
                });

                // Record E2E latency at true completion point (after strategy B processing + publish)
                if (entry.sendTimestamp() != null) {
                    long e2eMs = System.currentTimeMillis() - entry.sendTimestamp();
                    Span.current().setAttribute("poc.e2eLatencyMs", e2eMs);
                    pocMetrics.recordE2eLatency("B", entry.sendTimestamp());
                }

                log.debug("Processed event {} after {}ms delay, e2e={}ms, published to poc-stored-out",
                    entry.id(), storeDelayMs,
                    entry.sendTimestamp() != null ? System.currentTimeMillis() - entry.sendTimestamp() : "N/A");

                return entry.id();
            });
        } catch (Exception e) {
            log.error("Failed processing event {}", entry.id(), e);
            return null;
        }
    }

    private void simulateBusinessLatency() {
        long delayMs = runtimeTuning.getSimulatedBusinessLatencyMs();
        if (delayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
