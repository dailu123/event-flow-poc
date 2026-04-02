package com.hsbc.pluse.sample.poc;

import com.hsbc.pluse.model.Envelope;
import com.hsbc.pluse.model.ProcessingResult;
import com.hsbc.pluse.observe.EventFlowTracer;
import com.hsbc.pluse.pipeline.EventHandler;
import jakarta.annotation.PreDestroy;
import io.opentelemetry.api.trace.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Strategy B: Store-and-process entry handler.
 * Synchronous batch store by Kafka poll batch id/size.
 */
@Component
@ConditionalOnProperty(name = "eventflow.poc.enable-strategy-b", havingValue = "true", matchIfMissing = true)
public class PocStoredHandler implements EventHandler {

    private static final Logger log = LoggerFactory.getLogger(PocStoredHandler.class);

    private final EventStoreRepository repository;
    private final EventFlowTracer tracer;
    private final Map<String, BatchAccumulator> batchAccumulators = new ConcurrentHashMap<>();

    public PocStoredHandler(EventStoreRepository repository, EventFlowTracer tracer) {
        this.repository = repository;
        this.tracer = tracer;
    }

    @Override
    public ProcessingResult handle(Envelope envelope) {
        Span.current().setAttribute("poc.strategy", "B");
        Span.current().setAttribute("poc.phase", "process");

        String sendTraceId = extractText(envelope, "sendTraceId");
        String sendSpanId = extractText(envelope, "sendSpanId");
        String parentTraceId = sendTraceId != null ? sendTraceId : Span.current().getSpanContext().getTraceId();
        String parentSpanId = sendSpanId != null ? sendSpanId : Span.current().getSpanContext().getSpanId();
        Long sendTimestamp = envelope.getData().has("sendTimestamp")
                ? envelope.getData().get("sendTimestamp").asLong() : null;

        EventStoreRepository.StoreCommand command = new EventStoreRepository.StoreCommand(
                envelope.getId(),
                envelope.getSubject(),
                envelope.toString(),
                parentTraceId,
                parentSpanId,
                sendTimestamp
        );

        String batchId = envelope.getHeaders().get("kafka.poll.batch.id");
        int expectedBatchSize = parseBatchSize(envelope.getHeaders().get("kafka.poll.batch.size"));

        CompletableFuture<Void> persisted;
        if (batchId == null || expectedBatchSize <= 1) {
            persisted = persistSingle(command);
        } else {
            persisted = appendAndMaybeFlush(batchId, expectedBatchSize, command);
        }

        tracer.tracedWithLink("poc.db.store", "t_poc", envelope.getId(), parentTraceId, parentSpanId, () -> {
            Span.current().setAttribute("poc.strategy", "B");
            Span.current().setAttribute("poc.phase", "store");
            Span.current().setAttribute("poc.component", "db");
            if (batchId != null) {
                Span.current().setAttribute("poc.db.batchId", batchId);
                Span.current().setAttribute("poc.db.expectedBatchSize", expectedBatchSize);
            }
            waitPersisted(persisted);
            return null;
        });

        return new ProcessingResult.Filtered("stored-for-later");
    }

    private CompletableFuture<Void> persistSingle(EventStoreRepository.StoreCommand command) {
        CompletableFuture<Void> f = new CompletableFuture<>();
        try {
            repository.saveBatch(List.of(command));
            f.complete(null);
        } catch (Exception e) {
            f.completeExceptionally(e);
        }
        return f;
    }

    private CompletableFuture<Void> appendAndMaybeFlush(String batchId, int expectedBatchSize,
                                                        EventStoreRepository.StoreCommand command) {
        BatchAccumulator acc = batchAccumulators.compute(batchId, (id, existing) ->
                existing == null ? new BatchAccumulator(expectedBatchSize) : existing);

        CompletableFuture<Void> waiter = new CompletableFuture<>();
        List<EventStoreRepository.StoreCommand> toFlush = null;
        List<CompletableFuture<Void>> waitersToComplete = null;

        synchronized (acc) {
            acc.commands.add(command);
            acc.waiters.add(waiter);

            if (acc.commands.size() >= acc.expectedSize && acc.flushing.compareAndSet(false, true)) {
                toFlush = new ArrayList<>(acc.commands);
                waitersToComplete = new ArrayList<>(acc.waiters);
                acc.commands.clear();
                acc.waiters.clear();
            }
        }

        if (toFlush != null) {
            flushBatch(batchId, toFlush, waitersToComplete);
        }
        return waiter;
    }

    private void flushBatch(String batchId, List<EventStoreRepository.StoreCommand> commands,
                            List<CompletableFuture<Void>> waiters) {
        try {
            log.info("Persisting kafka poll batch synchronously, batchId={}, size={}", batchId, commands.size());
            repository.saveBatch(commands);
            waiters.forEach(w -> w.complete(null));
        } catch (Exception e) {
            waiters.forEach(w -> w.completeExceptionally(e));
        } finally {
            batchAccumulators.remove(batchId);
        }
    }

    private void waitPersisted(CompletableFuture<Void> persisted) {
        try {
            persisted.get();
        } catch (Exception e) {
            throw new RuntimeException("Batch DB flush failed", e);
        }
    }

    private void forceFlush(String batchId) {
        BatchAccumulator acc = batchAccumulators.get(batchId);
        if (acc == null) {
            return;
        }
        List<EventStoreRepository.StoreCommand> toFlush;
        List<CompletableFuture<Void>> waiters;
        synchronized (acc) {
            if (acc.commands.isEmpty() || !acc.flushing.compareAndSet(false, true)) {
                return;
            }
            toFlush = new ArrayList<>(acc.commands);
            waiters = new ArrayList<>(acc.waiters);
            acc.commands.clear();
            acc.waiters.clear();
        }
        log.warn("Force flushing partial batch, batchId={}, size={}", batchId, toFlush.size());
        flushBatch(batchId, toFlush, waiters);
    }

    private int parseBatchSize(String value) {
        if (value == null || value.isBlank()) {
            return 1;
        }
        try {
            return Math.max(1, Integer.parseInt(value));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private String extractText(Envelope envelope, String field) {
        if (envelope.getData() == null || !envelope.getData().has(field)) {
            return null;
        }
        String value = envelope.getData().get(field).asText(null);
        return (value == null || value.isBlank()) ? null : value;
    }

    @PreDestroy
    public void shutdown() {
        for (String batchId : List.copyOf(batchAccumulators.keySet())) {
            forceFlush(batchId);
        }
    }

    private static final class BatchAccumulator {
        private final int expectedSize;
        private final List<EventStoreRepository.StoreCommand> commands = new ArrayList<>();
        private final List<CompletableFuture<Void>> waiters = new ArrayList<>();
        private final AtomicBoolean flushing = new AtomicBoolean(false);

        private BatchAccumulator(int expectedSize) {
            this.expectedSize = expectedSize;
        }
    }
}
