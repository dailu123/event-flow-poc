package com.hsbc.pluse.sample;

import com.hsbc.pluse.dispatch.Dispatcher;
import com.hsbc.pluse.dispatch.DispatcherStats;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class MetricsController {

    private final Dispatcher dispatcher;

    public MetricsController(Dispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        Map<String, Object> response = new LinkedHashMap<>();

        // Dispatcher stats
        DispatcherStats stats = dispatcher.getStats();
        if (stats != null) {
            DispatcherStats.Snapshot snap = stats.snapshot();
            Map<String, Object> dispatcherInfo = new LinkedHashMap<>();
            dispatcherInfo.put("totalDispatched", snap.totalDispatched());
            dispatcherInfo.put("totalCompleted", snap.totalCompleted());
            dispatcherInfo.put("inflight", snap.totalDispatched() - snap.totalCompleted());
            dispatcherInfo.put("successCount", snap.totalSuccess());
            dispatcherInfo.put("filteredCount", snap.totalFiltered());
            dispatcherInfo.put("failedCount", snap.totalFailed());
            dispatcherInfo.put("duplicateCount", snap.totalDuplicate());
            dispatcherInfo.put("throughputPerSec", String.format("%.1f", snap.throughputPerSec()));

            Map<String, String> latency = new LinkedHashMap<>();
            latency.put("avgMs", String.format("%.2f", snap.avgLatencyMs()));
            latency.put("p50Ms", String.format("%.2f", snap.p50LatencyMs()));
            latency.put("p95Ms", String.format("%.2f", snap.p95LatencyMs()));
            latency.put("p99Ms", String.format("%.2f", snap.p99LatencyMs()));
            latency.put("maxMs", String.format("%.2f", snap.maxLatencyMs()));
            dispatcherInfo.put("latency", latency);

            response.put("dispatcher", dispatcherInfo);
        }

        // Worker pool info
        Map<String, Object> pools = new LinkedHashMap<>();
        pools.put("orderedWorkerQueues", dispatcher.orderedWorkerQueueSizes());
        pools.put("concurrentPoolActive", dispatcher.getConcurrentPoolActiveCount());
        pools.put("concurrentPoolSize", dispatcher.getConcurrentPoolSize());
        response.put("pools", pools);

        return response;
    }
}
