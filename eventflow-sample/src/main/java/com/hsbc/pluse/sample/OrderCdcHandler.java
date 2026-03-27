package com.hsbc.pluse.sample;

import com.hsbc.pluse.annotation.EventPipeline;
import com.hsbc.pluse.model.Destination;
import com.hsbc.pluse.model.Envelope;
import com.hsbc.pluse.model.ProcessingResult;
import com.hsbc.pluse.pipeline.EventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Pure business handler — ZERO framework/OTel dependency.
 * All tracing is handled automatically by the framework Dispatcher.
 */
@Component
@EventPipeline(routeKey = "t_order:*", ordered = true)
public class OrderCdcHandler implements EventHandler {

    private static final Logger log = LoggerFactory.getLogger(OrderCdcHandler.class);

    @Override
    public ProcessingResult handle(Envelope envelope) {
        String op = envelope.getHeaders().get("op");
        String pk = envelope.getHeaders().get("pk");

        if ("DELETE".equals(op)) {
            return new ProcessingResult.Filtered("skip delete for order " + pk);
        }

        log.info("Processing order CDC: op={}, pk={}", op, pk);

        Map<String, Object> payload = Map.of(
                "orderId", pk,
                "action", op,
                "processedAt", System.currentTimeMillis(),
                "data", envelope.getData().toString()
        );

        return new ProcessingResult.Success(List.of(
                new Destination("kafka", "order-sync-out", payload),
                new Destination("kafka", "order-audit-log", Map.of("orderId", pk, "action", op))
        ));
    }
}
