package com.hsbc.pluse.sample;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsbc.pluse.dispatch.Dispatcher;
import com.hsbc.pluse.dispatch.OffsetTracker;
import com.hsbc.pluse.model.Envelope;
import com.hsbc.pluse.model.ProcessingResult;
import com.hsbc.pluse.model.RouteKey;
import com.hsbc.pluse.model.SourceMeta;
import com.hsbc.pluse.observe.EventFlowTracer;
import com.hsbc.pluse.pipeline.PipelineDefinition;
import com.hsbc.pluse.routing.PipelineRegistry;
import com.hsbc.pluse.routing.Router;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Demo runner: simulates CDC events flowing through the full pipeline with OTel tracing.
 * No Kafka needed — generates fake events in-process.
 */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "demo.auto-run", havingValue = "true", matchIfMissing = true)
public class DemoRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoRunner.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Router router;
    private final PipelineRegistry registry;
    private final Dispatcher dispatcher;
    private final EventFlowTracer tracer;

    public DemoRunner(Router router, PipelineRegistry registry,
                      Dispatcher dispatcher, EventFlowTracer tracer) {
        this.router = router;
        this.registry = registry;
        this.dispatcher = dispatcher;
        this.tracer = tracer;
    }

    @Override
    public void run(String... args) throws Exception {
        log.info("========================================");
        log.info("  EventFlow Demo Runner Starting...");
        log.info("========================================");

        // Simulate 5 CDC events
        List<Map<String, Object>> events = List.of(
                Map.of("orderId", "ORD-1001", "amount", 99.9, "status", "CREATED",
                        "table", "t_order", "op", "INSERT", "pk", "1001"),
                Map.of("orderId", "ORD-1002", "amount", 250.0, "status", "CREATED",
                        "table", "t_order", "op", "INSERT", "pk", "1002"),
                Map.of("orderId", "ORD-1001", "amount", 99.9, "status", "PAID",
                        "table", "t_order", "op", "UPDATE", "pk", "1001"),
                Map.of("orderId", "ORD-1003", "amount", 15.5, "status", "CANCELLED",
                        "table", "t_order", "op", "DELETE", "pk", "1003"),
                Map.of("orderId", "ORD-1002", "amount", 250.0, "status", "SHIPPED",
                        "table", "t_order", "op", "UPDATE", "pk", "1002")
        );

        OffsetTracker tracker = new OffsetTracker("demo-partition-0");
        List<CompletableFuture<ProcessingResult>> futures = new java.util.ArrayList<>();

        for (int i = 0; i < events.size(); i++) {
            Map<String, Object> evt = events.get(i);
            long offset = i;

            // Build Envelope
            Envelope envelope = Envelope.builder()
                    .id(UUID.randomUUID().toString())
                    .source("demo://in-memory")
                    .type("cdc.row_change")
                    .time(Instant.now())
                    .subject(evt.get("table") + ":" + evt.get("pk"))
                    .data(MAPPER.valueToTree(evt))
                    .header("table", (String) evt.get("table"))
                    .header("op", (String) evt.get("op"))
                    .header("pk", (String) evt.get("pk"))
                    .sourceMeta(new SourceMeta("demo-topic", 0, offset))
                    .build();

            // Route → resolve pipeline
            RouteKey routeKey = router.route(envelope);
            List<PipelineDefinition> pipelines = registry.resolve(routeKey);

            log.info("[Event {}] {} op={} pk={} → matched {} pipeline(s)",
                    i, routeKey, evt.get("op"), evt.get("pk"), pipelines.size());

            if (pipelines.isEmpty()) {
                log.warn("[Event {}] No pipeline matched, skipping", i);
                continue;
            }

            tracker.register(offset, pipelines.size());

            // Dispatch each pipeline inside a parent trace span
            // The parent span wraps the entire event processing lifecycle:
            //   eventflow.event.process (parent)
            //     └─ eventflow.pipeline.execute (child, created by Dispatcher)
            //           ├─ eventflow.handler.handle
            //           └─ eventflow.outbound.publish
            for (PipelineDefinition pipeline : pipelines) {
                final Envelope copy = pipelines.size() > 1
                        ? envelope.withData(envelope.getData().deepCopy())
                        : envelope;
                final int idx = i;

                // Create parent span, then dispatch inside it so Dispatcher creates child spans
                CompletableFuture<ProcessingResult> future = tracer.traced(
                        "eventflow.event.process",
                        pipeline.routeKeyPattern(),
                        envelope.getId(),
                        () -> {
                            log.info("[Event {}] Processing via pipeline: {}", idx, pipeline.routeKeyPattern());
                            return dispatcher.dispatch(copy, pipeline, tracker);
                        }
                );

                // future here is the CompletableFuture from dispatch, unwrap it
                future.thenAccept(result -> logResult(idx, result));
                futures.add(future);
            }
        }

        // Wait for all async processing
        Thread.sleep(3000);

        // Check offset tracker state
        var committable = tracker.committableOffset();
        log.info("========================================");
        log.info("  Demo Complete!");
        log.info("  Committable offset: {}", committable.isPresent() ? committable.getAsLong() : "none");
        log.info("  Inflight remaining: {}", tracker.inflightCount());
        log.info("========================================");
        log.info("  Jaeger UI: http://localhost:16686");
        log.info("  Select service 'eventflow-sample' to view traces");
        log.info("========================================");

        // Give OTel exporter time to flush
        Thread.sleep(2000);
    }

    private void logResult(int index, ProcessingResult result) {
        if (result instanceof ProcessingResult.Success s) {
            log.info("[Event {}] SUCCESS -> {} destination(s): {}", index, s.outputs().size(),
                    s.outputs().stream().map(d -> d.channel() + ":" + d.target()).toList());
        } else if (result instanceof ProcessingResult.Filtered f) {
            log.info("[Event {}] FILTERED: {}", index, f.reason());
        } else if (result instanceof ProcessingResult.Failed f) {
            log.error("[Event {}] FAILED: {}", index, f.cause().getMessage());
        }
    }
}
