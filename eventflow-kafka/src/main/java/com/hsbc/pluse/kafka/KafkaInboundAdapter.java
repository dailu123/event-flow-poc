package com.hsbc.pluse.kafka;

import com.hsbc.pluse.dispatch.BackpressureController;
import com.hsbc.pluse.dispatch.Dispatcher;
import com.hsbc.pluse.dispatch.OffsetTracker;
import com.hsbc.pluse.idempotent.IdempotentFilter;
import com.hsbc.pluse.model.Envelope;
import com.hsbc.pluse.model.ProcessingResult;
import com.hsbc.pluse.model.RouteKey;
import com.hsbc.pluse.pipeline.PipelineDefinition;
import com.hsbc.pluse.port.InboundPort;
import com.hsbc.pluse.routing.PipelineRegistry;
import com.hsbc.pluse.routing.Router;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Kafka inbound adapter with:
 * - Parallel consumer via Dispatcher (Ordered/Concurrent)
 * - OffsetTracker sliding-window commit with ref-counting
 * - pause/resume backpressure (single flow-control gate)
 * - ConsumerRebalanceListener for safe partition handoff
 * - Graceful shutdown
 */
public class KafkaInboundAdapter implements InboundPort, Runnable {

    private static final Logger log = LoggerFactory.getLogger(KafkaInboundAdapter.class);

    private final KafkaConsumer<String, byte[]> consumer;
    private final KafkaEnvelopeConverter converter;
    private final IdempotentFilter idempotentFilter;
    private final Router router;
    private final PipelineRegistry registry;
    private final Dispatcher dispatcher;
    private final KafkaDltPublisher dltPublisher;
    private final BackpressureController backpressure;
    private final List<String> topics;
    private final int shutdownTimeoutSeconds;
    private final Duration commitInterval;

    private final ConcurrentHashMap<TopicPartition, OffsetTracker> trackers = new ConcurrentHashMap<>();
    private final Set<TopicPartition> pausedPartitions = ConcurrentHashMap.newKeySet();

    private volatile boolean running = false;
    private Thread pollThread;
    private Instant lastCommitTime = Instant.now();

    public KafkaInboundAdapter(KafkaConsumer<String, byte[]> consumer,
                               KafkaEnvelopeConverter converter,
                               IdempotentFilter idempotentFilter,
                               Router router,
                               PipelineRegistry registry,
                               Dispatcher dispatcher,
                               KafkaDltPublisher dltPublisher,
                               BackpressureController backpressure,
                               List<String> topics,
                               int shutdownTimeoutSeconds) {
        this.consumer = consumer;
        this.converter = converter;
        this.idempotentFilter = idempotentFilter;
        this.router = router;
        this.registry = registry;
        this.dispatcher = dispatcher;
        this.dltPublisher = dltPublisher;
        this.backpressure = backpressure;
        this.topics = topics;
        this.shutdownTimeoutSeconds = shutdownTimeoutSeconds;
        this.commitInterval = Duration.ofSeconds(5);
    }

    @Override
    public void start() {
        running = true;
        pollThread = new Thread(this, "ef-kafka-poll");
        pollThread.setDaemon(false);
        pollThread.start();
        log.info("KafkaInboundAdapter started, topics={}", topics);
    }

    @Override
    public void run() {
        consumer.subscribe(topics, new RebalanceHandler());

        while (running) {
            try {
                // Step 1: Backpressure check
                checkBackpressure();

                // Step 2: Poll
                ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(100));

                // Step 3: Process each record
                for (ConsumerRecord<String, byte[]> record : records) {
                    processRecord(record);
                }

                // Step 4: Periodic offset commit
                commitIfDue();

                // Step 5: Stall detection
                detectStalls();

            } catch (Exception e) {
                if (running) {
                    log.error("Error in poll loop", e);
                }
            }
        }

        // Final commit on shutdown
        commitCompletedOffsets();
        consumer.close();
        log.info("KafkaInboundAdapter poll loop exited");
    }

    private void processRecord(ConsumerRecord<String, byte[]> record) {
        TopicPartition tp = new TopicPartition(record.topic(), record.partition());
        OffsetTracker tracker = trackers.computeIfAbsent(
                tp, k -> new OffsetTracker(tp.toString()));

        // Deserialize (poison message protection)
        Envelope envelope;
        try {
            envelope = converter.convert(record);
        } catch (Exception e) {
            log.warn("Poison message at {}:{}, routing to DLT", tp, record.offset(), e);
            try {
                dltPublisher.sendToDlt(record, e);
            } catch (Exception dltEx) {
                log.error("DLT also failed for poison message {}:{}", tp, record.offset(), dltEx);
            }
            tracker.register(record.offset(), 1);
            tracker.complete(record.offset());
            return;
        }

        // Idempotent filter
        if (idempotentFilter.isDuplicate(envelope)) {
            tracker.register(record.offset(), 1);
            tracker.complete(record.offset());
            return;
        }

        // Route
        RouteKey routeKey = router.route(envelope);
        List<PipelineDefinition> pipelines = registry.resolve(routeKey);

        if (pipelines.isEmpty()) {
            tracker.register(record.offset(), 1);
            tracker.complete(record.offset());
            return;
        }

        // Register with ref-count = number of pipelines (fan-out safe)
        tracker.register(record.offset(), pipelines.size());

        // Dispatch to each matched pipeline
        for (PipelineDefinition pipeline : pipelines) {
            // Deep copy data for fan-out safety (immutable Envelope, but JsonNode is mutable)
            Envelope copy = pipelines.size() > 1
                    ? envelope.withData(envelope.getData().deepCopy())
                    : envelope;

            dispatcher.dispatch(copy, pipeline, tracker)
                    .whenComplete((result, ex) -> {
                        if (ex != null || (result instanceof ProcessingResult.Failed failed && failed.retryable())) {
                            Throwable cause = ex != null ? ex
                                    : ((ProcessingResult.Failed) result).cause();
                            try {
                                dltPublisher.sendToDlt(record, cause);
                                tracker.complete(record.offset());
                            } catch (Exception dltEx) {
                                log.error("DLT failed for offset={}, window will stall", record.offset(), dltEx);
                                // DO NOT complete — window stalls → backpressure → alert
                            }
                        }
                    });
        }
    }

    private void checkBackpressure() {
        for (Map.Entry<TopicPartition, OffsetTracker> entry : trackers.entrySet()) {
            TopicPartition tp = entry.getKey();
            int inflight = entry.getValue().inflightCount();
            boolean paused = pausedPartitions.contains(tp);

            BackpressureController.Action action = backpressure.evaluate(inflight, paused);
            switch (action) {
                case PAUSE -> {
                    consumer.pause(Collections.singleton(tp));
                    pausedPartitions.add(tp);
                    log.warn("Backpressure PAUSE: {} (inflight={})", tp, inflight);
                }
                case RESUME -> {
                    consumer.resume(Collections.singleton(tp));
                    pausedPartitions.remove(tp);
                    log.info("Backpressure RESUME: {} (inflight={})", tp, inflight);
                }
                default -> { /* NONE */ }
            }
        }
    }

    private void commitIfDue() {
        if (Duration.between(lastCommitTime, Instant.now()).compareTo(commitInterval) >= 0) {
            commitCompletedOffsets();
            lastCommitTime = Instant.now();
        }
    }

    private void commitCompletedOffsets() {
        Map<TopicPartition, OffsetAndMetadata> offsets = new HashMap<>();
        for (Map.Entry<TopicPartition, OffsetTracker> entry : trackers.entrySet()) {
            entry.getValue().committableOffset().ifPresent(offset ->
                    offsets.put(entry.getKey(), new OffsetAndMetadata(offset)));
        }
        if (!offsets.isEmpty()) {
            try {
                consumer.commitSync(offsets);
            } catch (Exception e) {
                log.error("Offset commit failed", e);
            }
        }
    }

    private void detectStalls() {
        Duration stallThreshold = Duration.ofMinutes(5);
        for (Map.Entry<TopicPartition, OffsetTracker> entry : trackers.entrySet()) {
            if (entry.getValue().oldestPendingAge().compareTo(stallThreshold) > 0) {
                entry.getValue().oldestPendingOffset().ifPresent(offset ->
                        log.error("STALL DETECTED: {} oldest pending offset={}, age={}",
                                entry.getKey(), offset, entry.getValue().oldestPendingAge()));
            }
        }
    }

    @Override
    public void stop() {
        log.info("KafkaInboundAdapter shutting down...");
        running = false;

        // 1. Pause all partitions
        try {
            Set<TopicPartition> all = consumer.assignment();
            if (!all.isEmpty()) {
                consumer.pause(all);
            }
        } catch (Exception e) {
            log.warn("Error pausing partitions during shutdown", e);
        }

        // 2. Wait for dispatcher to drain
        dispatcher.shutdown(Duration.ofSeconds(shutdownTimeoutSeconds));

        // 3. Final offset commit
        commitCompletedOffsets();

        // 4. Wait for poll thread to exit
        if (pollThread != null) {
            try {
                pollThread.join(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        log.info("KafkaInboundAdapter shutdown complete");
    }

    /**
     * Rebalance handler: drains in-flight messages for revoked partitions.
     */
    private class RebalanceHandler implements ConsumerRebalanceListener {

        @Override
        public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
            log.info("Partitions revoked: {}", partitions);
            for (TopicPartition tp : partitions) {
                OffsetTracker tracker = trackers.get(tp);
                if (tracker != null) {
                    tracker.awaitDrain(Duration.ofSeconds(10));
                }
            }

            // Commit final offsets for revoked partitions
            Map<TopicPartition, OffsetAndMetadata> offsets = new HashMap<>();
            for (TopicPartition tp : partitions) {
                OffsetTracker tracker = trackers.remove(tp);
                if (tracker != null) {
                    tracker.committableOffset().ifPresent(o ->
                            offsets.put(tp, new OffsetAndMetadata(o)));
                }
            }
            if (!offsets.isEmpty()) {
                consumer.commitSync(offsets);
            }

            pausedPartitions.removeAll(partitions);
        }

        @Override
        public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
            log.info("Partitions assigned: {}", partitions);
        }
    }
}
