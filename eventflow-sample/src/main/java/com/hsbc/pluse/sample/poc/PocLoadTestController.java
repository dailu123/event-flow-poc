package com.hsbc.pluse.sample.poc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsbc.pluse.observe.EventFlowTracer;
import io.opentelemetry.api.trace.Span;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * REST API for triggering POC load tests without restarting the application.
 *
 * Usage:
 *   curl -X POST http://localhost:8080/api/poc/load-test -H "Content-Type: application/json" \
 *     -d '{"total": 10000, "threads": 4}'
 *
 *   curl -X POST http://localhost:8080/api/poc/load-test -H "Content-Type: application/json" \
 *     -d '{"total": 1}'
 */
@RestController
@RequestMapping("/api/poc")
public class PocLoadTestController {

    private static final Logger log = LoggerFactory.getLogger(PocLoadTestController.class);
    private static final String TOPIC = "t-poc-events";

    @Value("${eventflow.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    private final EventStoreRepository repository;
    private final PocRuntimeTuning runtimeTuning;
    private final EventFlowTracer tracer;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicInteger runningTests = new AtomicInteger(0);

    public PocLoadTestController(EventStoreRepository repository, PocRuntimeTuning runtimeTuning, EventFlowTracer tracer) {
        this.repository = repository;
        this.runtimeTuning = runtimeTuning;
        this.tracer = tracer;
    }

    @PostMapping("/load-test")
    public ResponseEntity<?> runLoadTest(@RequestBody(required = false) LoadTestRequest request) {
        if (request == null) {
            request = new LoadTestRequest();
        }
        int total = request.total() != null && request.total() > 0 ? request.total() : 10000;
        int threads = request.threads() != null && request.threads() > 0 ? request.threads() : 4;
        boolean clean = request.clean() == null || request.clean();
        boolean waitAck = request.waitAck() != null && request.waitAck();
        String acks = request.acks() != null && !request.acks().isBlank()
            ? request.acks().trim() : (waitAck ? "1" : "0");
        Long dbLatencyMs = request.dbLatencyMs();
        Long businessLatencyMs = request.businessLatencyMs();
        Long schedulerDelayMs = request.schedulerDelayMs();

        if (dbLatencyMs != null) {
            runtimeTuning.setSimulatedDbLatencyMs(dbLatencyMs);
        }
        if (businessLatencyMs != null) {
            runtimeTuning.setSimulatedBusinessLatencyMs(businessLatencyMs);
        }
        if (schedulerDelayMs != null) {
            runtimeTuning.setSchedulerDelayMs(schedulerDelayMs);
        }

        if (!runningTests.compareAndSet(0, 1)) {
            return ResponseEntity.status(409).body(Map.of(
                "error", "A load test is already running. Wait for it to complete."));
        }

        // Run async so the API responds immediately
        CompletableFuture.runAsync(() -> {
            try {
                executeLoadTest(total, threads, clean, waitAck, acks);
            } finally {
                runningTests.set(0);
            }
        });

        return ResponseEntity.accepted().body(Map.of(
            "status", "started",
            "total", total,
            "threads", threads,
            "clean", clean,
            "waitAck", waitAck,
            "acks", acks,
            "dbLatencyMs", runtimeTuning.getSimulatedDbLatencyMs(),
            "businessLatencyMs", runtimeTuning.getSimulatedBusinessLatencyMs(),
            "schedulerDelayMs", runtimeTuning.getSchedulerDelayMs(),
            "message", "Load test started. Monitor progress in application logs and Grafana dashboard."
        ));
    }

    @GetMapping("/load-test/status")
    public ResponseEntity<?> getStatus() {
        boolean running = runningTests.get() > 0;
        long pending = repository.getPendingCount();
        long processed = repository.getProcessedCount();
        return ResponseEntity.ok(Map.of(
            "running", running,
            "strategyB_pending", pending,
            "strategyB_processed", processed,
            "dbLatencyMs", runtimeTuning.getSimulatedDbLatencyMs(),
            "businessLatencyMs", runtimeTuning.getSimulatedBusinessLatencyMs(),
            "schedulerDelayMs", runtimeTuning.getSchedulerDelayMs()
        ));
    }

    private void executeLoadTest(int totalEvents, int threads, boolean clean, boolean waitAck, String acks) {
        log.info("=== POC Load Test (API) ===");

        if (clean) {
            log.info("Cleaning event_store before test...");
            repository.truncate();
        }

        log.info("Starting load test: {} events, {} threads, topic={}, waitAck={}, acks={}",
            totalEvents, threads, TOPIC, waitAck, acks);

        Properties props = new Properties();
        props.put("bootstrap.servers", bootstrapServers);
        props.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        props.put("value.serializer", "org.apache.kafka.common.serialization.ByteArraySerializer");
        props.put("acks", acks);
        props.put("linger.ms", waitAck ? "5" : "20");
        props.put("batch.size", waitAck ? "16384" : "262144");

        KafkaProducer<String, byte[]> producer = new KafkaProducer<>(props);
        Random random = new Random();

        AtomicInteger sent = new AtomicInteger(0);
        AtomicInteger errors = new AtomicInteger(0);
        AtomicLong totalSendLatencyNanos = new AtomicLong(0);

        long startTime = System.currentTimeMillis();

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        int eventsPerThread = totalEvents / threads;
        CountDownLatch latch = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            int threadId = t;
            int startIdx = t * eventsPerThread;
            int endIdx = (t == threads - 1) ? totalEvents : startIdx + eventsPerThread;

            executor.submit(() -> {
                try {
                    for (int i = startIdx; i < endIdx; i++) {
                        String eventId = "E-" + i;
                        int amount = random.nextInt(50000);

                        try {
                            tracer.tracedProducer("poc.kafka.send", "t_poc", eventId, TOPIC, () -> {
                                Span.current().setAttribute("poc.phase", "send");
                                Span.current().setAttribute("poc.component", "producer");
                                Span.current().setAttribute("poc.load.threadId", threadId);
                                Span.current().setAttribute("poc.load.waitAck", waitAck);

                                long sendTimestamp = System.currentTimeMillis();
                                String sendTraceId = Span.current().getSpanContext().getTraceId();
                                String sendSpanId = Span.current().getSpanContext().getSpanId();

                                Map<String, Object> data = new LinkedHashMap<>();
                                data.put("amount", amount);
                                data.put("timestamp", sendTimestamp);
                                data.put("sendTimestamp", sendTimestamp);
                                data.put("sendTraceId", sendTraceId);
                                data.put("sendSpanId", sendSpanId);
                                data.put("producerTopic", TOPIC);
                                data.put("producerThreadId", threadId);

                                Map<String, Object> message = new LinkedHashMap<>();
                                message.put("table", "t_poc");
                                message.put("op", "INSERT");
                                message.put("pk", eventId);
                                message.put("data", data);

                                try {
                                    byte[] payload = objectMapper.writeValueAsBytes(message);
                                    long sendStart = System.nanoTime();
                                    if (waitAck) {
                                        Future<RecordMetadata> future = producer.send(new ProducerRecord<>(TOPIC, eventId, payload));
                                        future.get();
                                        long sendLatencyNanos = System.nanoTime() - sendStart;
                                        Span.current().setAttribute("poc.sendAckLatencyMs", sendLatencyNanos / 1_000_000.0);
                                        totalSendLatencyNanos.addAndGet(sendLatencyNanos);
                                    } else {
                                        producer.send(new ProducerRecord<>(TOPIC, eventId, payload), (metadata, ex) -> {
                                            if (ex != null) {
                                                errors.incrementAndGet();
                                            }
                                        });
                                    }
                                } catch (Exception sendEx) {
                                    throw new RuntimeException("send failed for " + eventId, sendEx);
                                }
                                return null;
                            });
                        } catch (Exception e) {
                            errors.incrementAndGet();
                            log.error("Failed to send event {}", eventId, e);
                        }

                        int count = sent.incrementAndGet();
                        if (count % 1000 == 0) {
                            long elapsed = System.currentTimeMillis() - startTime;
                            double rps = count * 1000.0 / elapsed;
                            log.info("Sent {}/{} events ({} err), {} msg/s",
                                count, totalEvents, errors.get(), (int) rps);
                        }
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                    log.error("Thread {} send error", threadId, e);
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (waitAck) {
            producer.flush();
            producer.close(Duration.ofSeconds(30));
        } else {
            producer.close(Duration.ofSeconds(1));
        }
        executor.shutdown();

        long sendDuration = System.currentTimeMillis() - startTime;
        double sendRps = totalEvents * 1000.0 / sendDuration;
        double avgSendLatencyMs = waitAck && sent.get() > 0
            ? totalSendLatencyNanos.get() / 1_000_000.0 / sent.get() : 0;

        log.info("========================================");
        log.info("=== SEND PHASE COMPLETE ===");
        log.info("Total sent:     {}", sent.get());
        log.info("Errors:         {}", errors.get());
        log.info("Duration:       {} ms", sendDuration);
        log.info("Throughput:     {} msg/s", (int) sendRps);
        log.info("Avg send lat:   {} ms", waitAck ? String.format("%.2f", avgSendLatencyMs) : "N/A (async no-ack)");
        log.info("========================================");

        log.info("=== LOAD TEST SEND COMPLETE (no wait) ===");
    }

    public record LoadTestRequest(
            Integer total,
            Integer threads,
            Boolean clean,
            Boolean waitAck,
            String acks,
            Long dbLatencyMs,
            Long businessLatencyMs,
            Long schedulerDelayMs) {
        public LoadTestRequest() {
            this(10000, 4, true, false, "0", null, null, null);
        }
    }
}
