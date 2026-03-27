package com.hsbc.pluse.sample;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsbc.pluse.dispatch.Dispatcher;
import com.hsbc.pluse.dispatch.DispatcherStats;
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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

@RestController
@RequestMapping("/api")
public class LoadTestController {

    private static final Logger log = LoggerFactory.getLogger(LoadTestController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AtomicLong OFFSET_SEQ = new AtomicLong(100_000);

    private final Router router;
    private final PipelineRegistry registry;
    private final Dispatcher dispatcher;
    private final EventFlowTracer tracer;

    public LoadTestController(Router router, PipelineRegistry registry,
                              Dispatcher dispatcher, EventFlowTracer tracer) {
        this.router = router;
        this.registry = registry;
        this.dispatcher = dispatcher;
        this.tracer = tracer;
    }

    /**
     * Load test with a single table/pipeline.
     * POST /api/load-test {"eventCount":500,"table":"t_order","concurrency":50}
     */
    @PostMapping("/load-test")
    public Map<String, Object> loadTest(@RequestBody Map<String, Object> body) {
        int eventCount = ((Number) body.getOrDefault("eventCount", 100)).intValue();
        String table = (String) body.getOrDefault("table", "t_order");
        int concurrency = ((Number) body.getOrDefault("concurrency", 20)).intValue();

        return runLoadTest(eventCount, concurrency, List.of(table), "load-test");
    }

    /**
     * Mixed load test: random mix of t_order + t_payment with varied amounts.
     * POST /api/load-test/mixed {"eventCount":200,"concurrency":20}
     */
    @PostMapping("/load-test/mixed")
    public Map<String, Object> mixedLoadTest(@RequestBody Map<String, Object> body) {
        int eventCount = ((Number) body.getOrDefault("eventCount", 100)).intValue();
        int concurrency = ((Number) body.getOrDefault("concurrency", 20)).intValue();

        return runLoadTest(eventCount, concurrency, List.of("t_order", "t_payment"), "load-test-mixed");
    }

    private Map<String, Object> runLoadTest(int eventCount, int concurrency,
                                             List<String> tables, String testName) {
        // Reset stats before test
        DispatcherStats stats = dispatcher.getStats();
        if (stats != null) stats.reset();

        return tracer.traced(
                "eventflow." + testName,
                String.join("+", tables),
                testName + "-" + eventCount,
                () -> {
                    String traceId = Span.current().getSpanContext().getTraceId();
                    log.info("Starting {}: {} events, concurrency={}, tables={}",
                            testName, eventCount, concurrency, tables);

                    tracer.setAttribute("loadtest.eventCount", (long) eventCount);
                    tracer.setAttribute("loadtest.concurrency", (long) concurrency);
                    tracer.setAttribute("loadtest.tables", String.join(",", tables));

                    ExecutorService submitPool = Executors.newFixedThreadPool(concurrency);
                    OffsetTracker tracker = new OffsetTracker("loadtest-partition-0");
                    Random rng = new Random();

                    long startNanos = System.nanoTime();
                    List<CompletableFuture<ResultEntry>> allFutures = new ArrayList<>();

                    // Submit events concurrently
                    for (int i = 0; i < eventCount; i++) {
                        final int idx = i;
                        String tbl = tables.get(rng.nextInt(tables.size()));

                        CompletableFuture<ResultEntry> future = CompletableFuture.supplyAsync(() -> {
                            long evtStart = System.nanoTime();
                            try {
                                return processEvent(idx, tbl, tracker, rng);
                            } finally {
                                // latency per event not needed here — DispatcherStats tracks it
                            }
                        }, submitPool);

                        allFutures.add(future);
                    }

                    // Wait for all to complete
                    List<ResultEntry> results = new ArrayList<>();
                    for (CompletableFuture<ResultEntry> f : allFutures) {
                        try {
                            results.add(f.get(30, TimeUnit.SECONDS));
                        } catch (Exception e) {
                            results.add(new ResultEntry("ERROR", 0));
                        }
                    }

                    long totalNanos = System.nanoTime() - startNanos;
                    double totalMs = totalNanos / 1_000_000.0;
                    double throughput = eventCount / (totalMs / 1000.0);

                    submitPool.shutdown();

                    // Aggregate results
                    long successCount = results.stream().filter(r -> "SUCCESS".equals(r.outcome)).count();
                    long filteredCount = results.stream().filter(r -> "FILTERED".equals(r.outcome)).count();
                    long failedCount = results.stream().filter(r -> "FAILED".equals(r.outcome)).count();
                    long duplicateCount = results.stream().filter(r -> "DUPLICATE".equals(r.outcome)).count();
                    long errorCount = results.stream().filter(r -> "ERROR".equals(r.outcome)).count();

                    // Get latency stats from DispatcherStats
                    DispatcherStats.Snapshot snap = stats != null ? stats.snapshot() : null;

                    // Record in trace
                    tracer.setAttribute("loadtest.totalMs", String.format("%.1f", totalMs));
                    tracer.setAttribute("loadtest.throughput", String.format("%.1f events/sec", throughput));
                    tracer.setAttribute("loadtest.successCount", successCount);
                    tracer.setAttribute("loadtest.filteredCount", filteredCount);
                    tracer.setAttribute("loadtest.failedCount", failedCount);

                    log.info("Load test complete: {} events in {:.1f}ms = {:.1f} events/sec",
                            eventCount, totalMs, throughput);

                    // Build response
                    Map<String, Object> response = new LinkedHashMap<>();
                    response.put("testName", testName);
                    response.put("totalEvents", eventCount);
                    response.put("concurrency", concurrency);
                    response.put("tables", tables);
                    response.put("successCount", successCount);
                    response.put("filteredCount", filteredCount);
                    response.put("failedCount", failedCount);
                    response.put("duplicateCount", duplicateCount);
                    response.put("errorCount", errorCount);
                    response.put("totalDurationMs", Math.round(totalMs));
                    response.put("throughput", String.format("%.1f events/sec", throughput));

                    if (snap != null) {
                        Map<String, Object> latency = new LinkedHashMap<>();
                        latency.put("avgMs", String.format("%.2f", snap.avgLatencyMs()));
                        latency.put("p50Ms", String.format("%.2f", snap.p50LatencyMs()));
                        latency.put("p95Ms", String.format("%.2f", snap.p95LatencyMs()));
                        latency.put("p99Ms", String.format("%.2f", snap.p99LatencyMs()));
                        latency.put("maxMs", String.format("%.2f", snap.maxLatencyMs()));
                        response.put("latency", latency);
                    }

                    response.put("orderedWorkerQueues", dispatcher.orderedWorkerQueueSizes());
                    response.put("traceId", traceId);
                    response.put("jaegerUrl", "http://localhost:16686/trace/" + traceId);

                    return response;
                }
        );
    }

    private ResultEntry processEvent(int idx, String table, OffsetTracker tracker, Random rng) {
        String idPrefix = "t_payment".equals(table) ? "PAY-" : "ORD-";
        String pk = String.valueOf(10000 + idx);
        String op = "INSERT";

        // Randomize amounts for payment to trigger different scenarios
        double amount = "t_payment".equals(table)
                ? new double[]{500, 5000, 15000, 25000, 35000, 60000}[rng.nextInt(6)]
                : 99.9;

        Map<String, Object> eventData = new LinkedHashMap<>();
        eventData.put(table.equals("t_payment") ? "paymentId" : "orderId", idPrefix + pk);
        eventData.put("op", op);
        eventData.put("status", "CREATED");
        eventData.put("table", table);
        eventData.put("pk", pk);
        eventData.put("amount", amount);

        long offset = OFFSET_SEQ.getAndIncrement();

        try {
            Envelope envelope = Envelope.builder()
                    .id(UUID.randomUUID().toString())
                    .source("loadtest://controller")
                    .type("cdc.row_change")
                    .time(Instant.now())
                    .subject(table + ":" + pk)
                    .data(MAPPER.valueToTree(eventData))
                    .header("table", table)
                    .header("op", op)
                    .header("pk", pk)
                    .sourceMeta(new SourceMeta("loadtest-topic", 0, offset))
                    .build();

            RouteKey routeKey = router.route(envelope);
            List<PipelineDefinition> pipelines = registry.resolve(routeKey);

            if (pipelines.isEmpty()) {
                return new ResultEntry("NO_MATCH", 0);
            }

            tracker.register(offset, pipelines.size());

            List<CompletableFuture<ProcessingResult>> futures = new ArrayList<>();
            for (PipelineDefinition pipeline : pipelines) {
                futures.add(dispatcher.dispatch(envelope, pipeline, tracker));
            }

            // Wait for pipeline completion
            for (CompletableFuture<ProcessingResult> f : futures) {
                ProcessingResult result = f.get(10, TimeUnit.SECONDS);
                if (result instanceof ProcessingResult.Filtered) {
                    return new ResultEntry("FILTERED", 0);
                } else if (result instanceof ProcessingResult.Failed) {
                    return new ResultEntry("FAILED", 0);
                }
            }
            return new ResultEntry("SUCCESS", 0);

        } catch (Exception e) {
            return new ResultEntry("ERROR", 0);
        }
    }

    private record ResultEntry(String outcome, long latencyNanos) {}
}
