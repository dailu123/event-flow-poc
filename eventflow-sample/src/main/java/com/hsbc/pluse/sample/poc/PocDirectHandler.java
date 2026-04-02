package com.hsbc.pluse.sample.poc;

import com.hsbc.pluse.annotation.EventPipeline;
import com.hsbc.pluse.model.Destination;
import com.hsbc.pluse.model.Envelope;
import com.hsbc.pluse.model.ProcessingResult;
import com.hsbc.pluse.observe.EventFlowTracer;
import com.hsbc.pluse.pipeline.EventHandler;
import io.opentelemetry.api.trace.Span;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Strategy A: Direct processing handler.
 * Processes events synchronously immediately upon reception.
 */
@Component
@ConditionalOnProperty(name = "eventflow.poc.enable-strategy-a", havingValue = "true", matchIfMissing = true)
@EventPipeline(routeKey = "t_poc:*", ordered = false, executionGroup = "poc-A")
public class PocDirectHandler implements EventHandler {

    private final PocMetrics pocMetrics;
    private final EventFlowTracer tracer;
    private final PocRuntimeTuning runtimeTuning;

    public PocDirectHandler(PocMetrics pocMetrics, EventFlowTracer tracer, PocRuntimeTuning runtimeTuning) {
        this.pocMetrics = pocMetrics;
        this.tracer = tracer;
        this.runtimeTuning = runtimeTuning;
    }

    @Override
    public ProcessingResult handle(Envelope envelope) {
        // Mark this span as Strategy A
        Span.current().setAttribute("poc.strategy", "A");
        Span.current().setAttribute("poc.phase", "process");

        String sendTraceId = extractText(envelope, "sendTraceId");
        String sendSpanId = extractText(envelope, "sendSpanId");
        if (sendTraceId != null && sendSpanId != null) {
            return tracer.tracedWithLink("poc.strategyA.process", "t_poc", envelope.getId(), sendTraceId, sendSpanId,
                () -> processBusiness(envelope));
        }
        return processBusiness(envelope);
    }

    private ProcessingResult processBusiness(Envelope envelope) {
        return tracer.tracedInternal("poc.business.process", () -> {
            Span.current().setAttribute("poc.strategy", "A");
            Span.current().setAttribute("poc.phase", "process");
            Span.current().setAttribute("poc.component", "business");

            simulateBusinessLatency();

            // Record E2E latency
            if (envelope.getData().has("sendTimestamp")) {
                pocMetrics.recordE2eLatency("A", envelope.getData().get("sendTimestamp").asLong());
            }

            // Publish to outbound immediately
            String pk = envelope.getHeaders().getOrDefault("pk", envelope.getId());
            return new ProcessingResult.Success(List.of(
                new Destination("kafka", "poc-direct-out", Map.of(
                    "strategy", "A",
                    "eventId", envelope.getId(),
                    "pk", pk
                ))
            ));
        });
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

    private String extractText(Envelope envelope, String field) {
        if (envelope.getData() == null || !envelope.getData().has(field)) {
            return null;
        }
        String value = envelope.getData().get(field).asText(null);
        return (value == null || value.isBlank()) ? null : value;
    }
}
