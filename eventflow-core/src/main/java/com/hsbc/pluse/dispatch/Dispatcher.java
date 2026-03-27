package com.hsbc.pluse.dispatch;

import com.hsbc.pluse.idempotent.IdempotentFilter;
import com.hsbc.pluse.model.Destination;
import com.hsbc.pluse.model.Envelope;
import com.hsbc.pluse.model.ProcessingResult;
import com.hsbc.pluse.port.CompositeOutboundPublisher;
import com.hsbc.pluse.pipeline.PipelineDefinition;
import com.hsbc.pluse.pipeline.PipelineStep;
import com.hsbc.pluse.observe.EventFlowTracer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.opentelemetry.context.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Dual-mode dispatcher with full framework-level tracing.
 *
 * ALL tracing is automatic at framework level — business code (EventHandler, PipelineStep)
 * has ZERO dependency on OpenTelemetry. Every project using this framework gets full tracing for free.
 *
 * Auto-traced span tree per event:
 *   eventflow.pipeline.execute
 *     ├─ eventflow.idempotent.check     (dedup key, duplicate? yes/no)
 *     ├─ eventflow.pre-steps            (each step individually traced with class name)
 *     │   ├─ pre-step[0] ExchangeRateEnrichStep
 *     │   ├─ pre-step[1] FraudCheckStep
 *     │   └─ ...
 *     ├─ eventflow.handler.handle       (handler class, op, pk, result type)
 *     ├─ eventflow.outbound.publish     (per destination with channel/target)
 *     │   ├─ send → kafka:topic-a
 *     │   └─ send → kafka:topic-b
 *     └─ eventflow.offset.complete      (offset, committable, inflight)
 */
public class Dispatcher {

    private static final Logger log = LoggerFactory.getLogger(Dispatcher.class);

    private final ExecutorService[] orderedWorkers;
    private final ExecutorService concurrentPool;
    private final CompositeOutboundPublisher outboundPublisher;
    private final MeterRegistry meterRegistry;
    private final EventFlowTracer tracer;       // nullable
    private final IdempotentFilter idempotentFilter; // nullable
    private final DispatcherStats stats;         // nullable

    public Dispatcher(DispatcherConfig config,
                      CompositeOutboundPublisher outboundPublisher,
                      MeterRegistry meterRegistry,
                      EventFlowTracer tracer,
                      IdempotentFilter idempotentFilter,
                      DispatcherStats stats) {
        this.outboundPublisher = outboundPublisher;
        this.meterRegistry = meterRegistry;
        this.tracer = tracer;
        this.idempotentFilter = idempotentFilter;
        this.stats = stats;

        int orderedSlots = config.getOrderedWorkerCount();
        this.orderedWorkers = new ExecutorService[orderedSlots];
        for (int i = 0; i < orderedSlots; i++) {
            final int slot = i;
            this.orderedWorkers[i] = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "ef-ordered-" + slot);
                t.setDaemon(true);
                return t;
            });
        }

        this.concurrentPool = new ThreadPoolExecutor(
                config.getConcurrentCoreSize(),
                config.getConcurrentMaxSize(),
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                new AtomicThreadFactory("ef-concurrent")
        );
    }

    public CompletableFuture<ProcessingResult> dispatch(
            Envelope envelope,
            PipelineDefinition pipeline,
            OffsetTracker tracker) {

        final Context parentContext = tracer != null ? tracer.currentContext() : null;
        final long dispatchTimeNanos = System.nanoTime();

        if (stats != null) stats.recordDispatched();

        String orderKey = null;
        int slot = -1;
        if (pipeline.ordered()) {
            orderKey = resolveOrderKey(envelope, pipeline.orderKeyExpr());
            slot = Math.floorMod(orderKey.hashCode(), orderedWorkers.length);
        }

        // Framework auto-trace: dispatch routing decision
        if (tracer != null) {
            Map<String, String> attrs = new HashMap<>();
            attrs.put("dispatch.mode", pipeline.ordered() ? "ORDERED" : "CONCURRENT");
            attrs.put("dispatch.routeKey", pipeline.routeKeyPattern());
            attrs.put("dispatch.eventId", envelope.getId());
            if (pipeline.ordered()) {
                attrs.put("dispatch.orderKey", orderKey);
                attrs.put("dispatch.workerSlot", String.valueOf(slot));
                if (orderedWorkers[slot] instanceof ThreadPoolExecutor tpe) {
                    attrs.put("dispatch.workerQueueSize", String.valueOf(tpe.getQueue().size()));
                }
            } else {
                if (concurrentPool instanceof ThreadPoolExecutor tpe) {
                    attrs.put("dispatch.concurrentPoolActive", String.valueOf(tpe.getActiveCount()));
                    attrs.put("dispatch.concurrentPoolSize", String.valueOf(tpe.getPoolSize()));
                    attrs.put("dispatch.concurrentQueueSize", String.valueOf(tpe.getQueue().size()));
                }
            }
            tracer.tracedInternal("eventflow.dispatch.route", attrs, () -> null);
        }

        if (pipeline.ordered()) {
            return CompletableFuture.supplyAsync(
                    () -> executePipeline(envelope, pipeline, tracker, parentContext, dispatchTimeNanos),
                    orderedWorkers[slot]
            );
        } else {
            return CompletableFuture.supplyAsync(
                    () -> executePipeline(envelope, pipeline, tracker, parentContext, dispatchTimeNanos),
                    concurrentPool
            );
        }
    }

    private ProcessingResult executePipeline(
            Envelope envelope,
            PipelineDefinition pipeline,
            OffsetTracker tracker,
            Context parentContext,
            long dispatchTimeNanos) {

        long queueWaitNanos = System.nanoTime() - dispatchTimeNanos;
        long executeStartNanos = System.nanoTime();

        if (tracer == null) {
            ProcessingResult result = doExecutePipeline(envelope, pipeline, tracker);
            if (stats != null) {
                stats.recordCompleted(resultType(result), System.nanoTime() - executeStartNanos);
            }
            return result;
        }

        Map<String, String> attrs = new HashMap<>();
        attrs.put("pipeline.routeKey", pipeline.routeKeyPattern());
        attrs.put("pipeline.eventId", envelope.getId());
        attrs.put("pipeline.subject", envelope.getSubject() != null ? envelope.getSubject() : "");
        attrs.put("pipeline.source", envelope.getSource() != null ? envelope.getSource() : "");
        attrs.put("pipeline.ordered", String.valueOf(pipeline.ordered()));
        attrs.put("pipeline.thread", Thread.currentThread().getName());
        attrs.put("pipeline.preStepCount", String.valueOf(pipeline.preSteps().size()));
        attrs.put("pipeline.handlerClass", pipeline.handler().getClass().getSimpleName());
        attrs.put("pipeline.queueWaitMs", String.format("%.2f", queueWaitNanos / 1_000_000.0));

        return tracer.tracedChild("eventflow.pipeline.execute", parentContext, attrs, () -> {
            ProcessingResult result = doExecutePipeline(envelope, pipeline, tracker);
            if (stats != null) {
                stats.recordCompleted(resultType(result), System.nanoTime() - executeStartNanos);
            }
            return result;
        });
    }

    private ProcessingResult doExecutePipeline(
            Envelope envelope,
            PipelineDefinition pipeline,
            OffsetTracker tracker) {

        Timer.Sample sample = meterRegistry != null ? Timer.start(meterRegistry) : null;
        String routeKey = pipeline.routeKeyPattern();
        long offset = envelope.getSourceMeta() != null ? envelope.getSourceMeta().offset() : -1;

        try {
            // ====== Step 0: Idempotent check (framework auto-traced) ======
            if (idempotentFilter != null) {
                boolean isDup = checkIdempotent(envelope);
                if (isDup) {
                    completeOffset(tracker, offset, routeKey, "DUPLICATE");
                    recordTimer(sample, routeKey, "duplicate");
                    return new ProcessingResult.Filtered("duplicate event: " + idempotentFilter.deduplicationKey(envelope));
                }
            }

            // ====== Step 1: Pre-steps (framework auto-traced per step) ======
            Envelope current = envelope;
            if (!pipeline.preSteps().isEmpty()) {
                current = executePreSteps(current, pipeline);
                if (current == null) {
                    completeOffset(tracker, offset, routeKey, "FILTERED");
                    recordTimer(sample, routeKey, "filtered");
                    return new ProcessingResult.Filtered("filtered at pre-step");
                }
            }

            // ====== Step 2: Handler (framework auto-traced) ======
            final Envelope handlerInput = current;
            ProcessingResult result = executeHandler(handlerInput, pipeline);

            // ====== Step 3: Outbound publish (framework auto-traced per destination) ======
            if (result instanceof ProcessingResult.Success success && !success.outputs().isEmpty()) {
                publishOutputs(success.outputs(), envelope.getId());
            }

            // ====== Step 4: Offset complete (framework auto-traced) ======
            completeOffset(tracker, offset, routeKey, resultType(result));
            recordTimer(sample, routeKey, "success");
            return result;

        } catch (Exception e) {
            log.error("Pipeline execution failed: routeKey={}, offset={}", routeKey, offset, e);
            if (tracer != null) {
                tracer.setAttribute("pipeline.error", e.getMessage());
            }
            recordTimer(sample, routeKey, "error");
            return new ProcessingResult.Failed(e, true);
        }
    }

    // ─── Framework auto-tracing: Idempotent ───────────────────────────

    private boolean checkIdempotent(Envelope envelope) {
        if (tracer != null) {
            String dedupKey = idempotentFilter.deduplicationKey(envelope);
            return tracer.tracedInternal("eventflow.idempotent.check",
                    Map.of("idempotent.dedupKey", dedupKey,
                            "idempotent.eventId", envelope.getId(),
                            "idempotent.filterClass", idempotentFilter.getClass().getSimpleName()),
                    () -> {
                        boolean dup = idempotentFilter.isDuplicate(envelope);
                        tracer.setAttribute("idempotent.isDuplicate", dup ? "true" : "false");
                        if (dup) {
                            tracer.addEvent("duplicate-detected", Map.of(
                                    "dedupKey", dedupKey,
                                    "action", "SKIP"));
                        } else {
                            tracer.addEvent("event-accepted", Map.of(
                                    "dedupKey", dedupKey,
                                    "action", "PROCESS"));
                        }
                        return dup;
                    });
        }
        return idempotentFilter.isDuplicate(envelope);
    }

    // ─── Framework auto-tracing: Pre-steps ────────────────────────────

    private Envelope executePreSteps(Envelope envelope, PipelineDefinition pipeline) {
        if (tracer != null) {
            return tracer.tracedInternal("eventflow.pre-steps",
                    Map.of("preSteps.count", String.valueOf(pipeline.preSteps().size())),
                    () -> {
                        Envelope current = envelope;
                        for (int i = 0; i < pipeline.preSteps().size(); i++) {
                            PipelineStep step = pipeline.preSteps().get(i);
                            final int idx = i;
                            final Envelope input = current;
                            String stepClass = step.getClass().getSimpleName();
                            current = tracer.tracedInternal(
                                    "eventflow.pre-step[" + i + "] " + stepClass,
                                    Map.of("preStep.index", String.valueOf(idx),
                                            "preStep.class", stepClass),
                                    () -> {
                                        Envelope out = step.process(input);
                                        tracer.setAttribute("preStep.result",
                                                out != null ? "PASS" : "FILTERED");
                                        return out;
                                    });
                            if (current == null) {
                                tracer.setAttribute("preSteps.filteredAt", String.valueOf(i));
                                tracer.setAttribute("preSteps.filteredBy", stepClass);
                                return null;
                            }
                        }
                        tracer.setAttribute("preSteps.result", "ALL_PASSED");
                        return current;
                    });
        }
        Envelope current = envelope;
        for (PipelineStep step : pipeline.preSteps()) {
            current = step.process(current);
            if (current == null) return null;
        }
        return current;
    }

    // ─── Framework auto-tracing: Handler ──────────────────────────────

    private ProcessingResult executeHandler(Envelope envelope, PipelineDefinition pipeline) {
        if (tracer != null) {
            return tracer.tracedInternal("eventflow.handler.handle",
                    Map.of("handler.class", pipeline.handler().getClass().getSimpleName(),
                            "handler.eventId", envelope.getId(),
                            "handler.op", envelope.getHeaders().getOrDefault("op", ""),
                            "handler.pk", envelope.getHeaders().getOrDefault("pk", ""),
                            "handler.table", envelope.getHeaders().getOrDefault("table", "")),
                    () -> {
                        ProcessingResult r = pipeline.handler().handle(envelope);
                        // Auto-record result details
                        if (r instanceof ProcessingResult.Success s) {
                            tracer.setAttribute("handler.result", "SUCCESS");
                            tracer.setAttribute("handler.outputCount", s.outputs().size());
                        } else if (r instanceof ProcessingResult.Filtered f) {
                            tracer.setAttribute("handler.result", "FILTERED");
                            tracer.setAttribute("handler.filterReason", f.reason());
                        } else if (r instanceof ProcessingResult.Failed f) {
                            tracer.setAttribute("handler.result", "FAILED");
                            tracer.setAttribute("handler.error", f.cause().getMessage());
                        }
                        return r;
                    });
        }
        return pipeline.handler().handle(envelope);
    }

    // ─── Framework auto-tracing: Outbound publish ─────────────────────

    private void publishOutputs(List<Destination> outputs, String eventId) {
        if (tracer != null) {
            tracer.tracedInternal("eventflow.outbound.publish",
                    Map.of("outbound.destinationCount", String.valueOf(outputs.size()),
                            "outbound.eventId", eventId),
                    () -> {
                        for (int i = 0; i < outputs.size(); i++) {
                            Destination dest = outputs.get(i);
                            tracer.tracedInternal(
                                    "eventflow.outbound.send → " + dest.channel() + ":" + dest.target(),
                                    Map.of("destination.index", String.valueOf(i),
                                            "destination.channel", dest.channel(),
                                            "destination.target", dest.target()),
                                    () -> {
                                        outboundPublisher.publish(List.of(dest));
                                        return null;
                                    });
                        }
                        return null;
                    });
        } else {
            outboundPublisher.publish(outputs);
        }
    }

    // ─── Framework auto-tracing: Offset tracking ──────────────────────

    private void completeOffset(OffsetTracker tracker, long offset, String routeKey, String resultType) {
        if (offset < 0) return;
        if (tracer != null) {
            tracer.tracedInternal("eventflow.offset.complete",
                    Map.of("offset.value", String.valueOf(offset),
                            "offset.partition", tracker.getPartitionId(),
                            "offset.resultType", resultType),
                    () -> {
                        tracker.complete(offset);
                        long committable = tracker.committableOffset().orElse(-1);
                        tracer.setAttribute("offset.committable", committable);
                        tracer.setAttribute("offset.inflight", (long) tracker.inflightCount());
                        return null;
                    });
        } else {
            tracker.complete(offset);
        }
    }

    // ─── Utilities ────────────────────────────────────────────────────

    private String resolveOrderKey(Envelope env, String expr) {
        return env.getHeaders().getOrDefault("table", "")
                + ":" + env.getHeaders().getOrDefault("pk", "");
    }

    private String resultType(ProcessingResult result) {
        if (result instanceof ProcessingResult.Success) return "SUCCESS";
        if (result instanceof ProcessingResult.Filtered) return "FILTERED";
        if (result instanceof ProcessingResult.Failed) return "FAILED";
        return "UNKNOWN";
    }

    private void recordTimer(Timer.Sample sample, String routeKey, String status) {
        if (sample != null && meterRegistry != null) {
            sample.stop(Timer.builder("eventflow.pipeline.duration")
                    .tag("routeKey", routeKey)
                    .tag("status", status)
                    .register(meterRegistry));
        }
    }

    public DispatcherStats getStats() {
        return stats;
    }

    public int getConcurrentPoolActiveCount() {
        if (concurrentPool instanceof ThreadPoolExecutor tpe) {
            return tpe.getActiveCount();
        }
        return -1;
    }

    public int getConcurrentPoolSize() {
        if (concurrentPool instanceof ThreadPoolExecutor tpe) {
            return tpe.getPoolSize();
        }
        return -1;
    }

    public Map<Integer, Integer> orderedWorkerQueueSizes() {
        Map<Integer, Integer> sizes = new HashMap<>();
        for (int i = 0; i < orderedWorkers.length; i++) {
            if (orderedWorkers[i] instanceof ThreadPoolExecutor tpe) {
                sizes.put(i, tpe.getQueue().size());
            }
        }
        return sizes;
    }

    public void shutdown(Duration timeout) {
        log.info("Dispatcher shutting down, timeout={}s", timeout.toSeconds());
        for (ExecutorService w : orderedWorkers) { w.shutdown(); }
        concurrentPool.shutdown();
        try {
            for (ExecutorService w : orderedWorkers) {
                w.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS);
            }
            concurrentPool.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static class AtomicThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger(0);
        private final String prefix;
        AtomicThreadFactory(String prefix) { this.prefix = prefix; }
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, prefix + "-" + counter.getAndIncrement());
            t.setDaemon(true);
            return t;
        }
    }
}
