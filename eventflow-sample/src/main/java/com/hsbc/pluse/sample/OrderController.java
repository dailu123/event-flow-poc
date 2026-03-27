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
import io.opentelemetry.api.trace.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

@RestController
@RequestMapping("/api")
public class OrderController {

    private static final Logger log = LoggerFactory.getLogger(OrderController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AtomicLong OFFSET_SEQ = new AtomicLong(1000);

    private final Router router;
    private final PipelineRegistry registry;
    private final Dispatcher dispatcher;
    private final EventFlowTracer tracer;

    public OrderController(Router router, PipelineRegistry registry,
                           Dispatcher dispatcher, EventFlowTracer tracer) {
        this.router = router;
        this.registry = registry;
        this.dispatcher = dispatcher;
        this.tracer = tracer;
    }

    @PostMapping({"/order", "/payment"})
    public Map<String, Object> submitEvent(@RequestBody Map<String, Object> body,
                                            jakarta.servlet.http.HttpServletRequest request) {
        boolean isPayment = request.getRequestURI().contains("/payment");
        String table = isPayment ? "t_payment" : "t_order";
        String idPrefix = isPayment ? "PAY-" : "ORD-";

        String orderId = (String) body.getOrDefault(isPayment ? "paymentId" : "orderId",
                idPrefix + System.currentTimeMillis());
        String op = (String) body.getOrDefault("op", "INSERT");
        String status = (String) body.getOrDefault("status", "CREATED");
        String pk = orderId.replace(idPrefix, "");

        Map<String, Object> eventData = new LinkedHashMap<>(body);
        eventData.putIfAbsent(isPayment ? "paymentId" : "orderId", orderId);
        eventData.putIfAbsent("op", op);
        eventData.putIfAbsent("status", status);
        eventData.putIfAbsent("table", table);
        eventData.putIfAbsent("pk", pk);

        long offset = OFFSET_SEQ.getAndIncrement();

        // === Root span: the entire API request processing ===
        return tracer.traced(
                "eventflow.api.order",
                table + ":" + op,
                orderId,
                () -> {
                    String traceId = Span.current().getSpanContext().getTraceId();

                    // Step 1: Build envelope
                    Envelope envelope = tracer.tracedInternal(
                            "eventflow.api.buildEnvelope",
                            Map.of("api.orderId", orderId,
                                    "api.op", op,
                                    "api.status", status,
                                    "api.pk", pk,
                                    "api.offset", String.valueOf(offset)),
                            () -> Envelope.builder()
                                    .id(UUID.randomUUID().toString())
                                    .source("api://order-controller")
                                    .type("cdc.row_change")
                                    .time(Instant.now())
                                    .subject(table + ":" + pk)
                                    .data(MAPPER.valueToTree(eventData))
                                    .header("table", table)
                                    .header("op", op)
                                    .header("pk", pk)
                                    .sourceMeta(new SourceMeta("api-topic", 0, offset))
                                    .build());

                    // Step 2: Route to pipeline
                    List<PipelineDefinition> pipelines = tracer.tracedInternal(
                            "eventflow.api.route",
                            Map.of("api.routerClass", router.getClass().getSimpleName()),
                            () -> {
                                RouteKey routeKey = router.route(envelope);
                                tracer.setAttribute("api.resolvedRouteKey", routeKey.toString());
                                List<PipelineDefinition> matched = registry.resolve(routeKey);
                                tracer.setAttribute("api.matchedPipelines", (long) matched.size());
                                for (int i = 0; i < matched.size(); i++) {
                                    tracer.addEvent("pipeline-matched", Map.of(
                                            "pipeline.index", String.valueOf(i),
                                            "pipeline.routeKey", matched.get(i).routeKeyPattern(),
                                            "pipeline.ordered", String.valueOf(matched.get(i).ordered())));
                                }
                                return matched;
                            });

                    if (pipelines.isEmpty()) {
                        tracer.setAttribute("api.result", "NO_MATCH");
                        return Map.of("status", "NO_MATCH",
                                "traceId", traceId,
                                "jaegerUrl", "http://localhost:16686/trace/" + traceId,
                                "message", "No pipeline matched");
                    }

                    // Step 3: Dispatch
                    OffsetTracker tracker = new OffsetTracker("api-partition-0");
                    tracker.register(offset, pipelines.size());

                    List<Map<String, Object>> results = tracer.tracedInternal(
                            "eventflow.api.dispatch",
                            Map.of("api.pipelineCount", String.valueOf(pipelines.size())),
                            () -> {
                                List<CompletableFuture<ProcessingResult>> futures = new ArrayList<>();
                                for (PipelineDefinition pipeline : pipelines) {
                                    futures.add(dispatcher.dispatch(envelope, pipeline, tracker));
                                }
                                List<Map<String, Object>> res = new ArrayList<>();
                                for (int i = 0; i < futures.size(); i++) {
                                    try {
                                        ProcessingResult result = futures.get(i).get();
                                        res.add(formatResult(result));
                                    } catch (Exception e) {
                                        res.add(Map.of("status", "ERROR", "message", e.getMessage()));
                                    }
                                }
                                return res;
                            });

                    tracer.setAttribute("api.result", "OK");

                    Map<String, Object> response = new LinkedHashMap<>();
                    response.put("status", "OK");
                    response.put("traceId", traceId);
                    response.put("jaegerUrl", "http://localhost:16686/trace/" + traceId);
                    response.put("routeKey", table + ":" + op);
                    response.put("eventId", envelope.getId());
                    response.put("pipelines", results);
                    return response;
                }
        );
    }

    private Map<String, Object> formatResult(ProcessingResult result) {
        if (result instanceof ProcessingResult.Success s) {
            return Map.of("outcome", "SUCCESS", "destinations",
                    s.outputs().stream().map(d -> d.channel() + ":" + d.target()).toList());
        } else if (result instanceof ProcessingResult.Filtered f) {
            return Map.of("outcome", "FILTERED", "reason", f.reason());
        } else if (result instanceof ProcessingResult.Failed f) {
            return Map.of("outcome", "FAILED", "error", f.cause().getMessage());
        }
        return Map.of("outcome", "UNKNOWN");
    }
}
