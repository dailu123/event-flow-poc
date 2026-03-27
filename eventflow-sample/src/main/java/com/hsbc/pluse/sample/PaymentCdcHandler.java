package com.hsbc.pluse.sample;

import com.hsbc.pluse.model.Destination;
import com.hsbc.pluse.model.Envelope;
import com.hsbc.pluse.model.ProcessingResult;
import com.hsbc.pluse.pipeline.EventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Pure business handler — ZERO framework/OTel dependency.
 * All tracing is handled automatically by the framework Dispatcher.
 *
 * Scenarios:
 * - Normal payment: processes with enriched data from pre-steps
 * - amount > 30000: simulates slow downstream call (~200ms)
 * - status = "TIMEOUT": simulates a downstream timeout exception
 * - status = "DB_ERROR": simulates a database write failure
 */
public class PaymentCdcHandler implements EventHandler {

    private static final Logger log = LoggerFactory.getLogger(PaymentCdcHandler.class);

    @Override
    public ProcessingResult handle(Envelope envelope) {
        String op = envelope.getHeaders().get("op");
        String pk = envelope.getHeaders().get("pk");
        String status = envelope.getData().has("status") ? envelope.getData().get("status").asText() : "";
        double amount = envelope.getData().has("amount") ? envelope.getData().get("amount").asDouble() : 0;

        log.info("Processing payment: pk={}, op={}, amount={}, status={}", pk, op, amount, status);

        // Slow path for large amounts
        if (amount > 30000) {
            log.warn("Large payment settlement, amount={}, simulating 200ms delay", amount);
            try { Thread.sleep(200); } catch (InterruptedException ignored) {}
        }

        // Error: downstream timeout
        if ("TIMEOUT".equals(status)) {
            try { Thread.sleep(100); } catch (InterruptedException ignored) {}
            throw new RuntimeException("Downstream timeout: payment-settlement-service did not respond within 5000ms");
        }

        // Error: database failure
        if ("DB_ERROR".equals(status)) {
            throw new RuntimeException("Database error: Failed to write to payment_ledger (connection refused)");
        }

        // Build enriched output
        String usdAmount = envelope.getData().has("usd_amount")
                ? envelope.getData().get("usd_amount").asText() : "N/A";
        String complianceCode = envelope.getHeaders().getOrDefault("compliance_code", "N/A");

        Map<String, Object> syncPayload = Map.of(
                "paymentId", pk,
                "action", op,
                "amount", amount,
                "usdAmount", usdAmount,
                "complianceCode", complianceCode,
                "fraudRisk", envelope.getHeaders().getOrDefault("fraud_risk", "N/A"),
                "processedAt", System.currentTimeMillis()
        );

        Map<String, Object> auditPayload = Map.of(
                "paymentId", pk,
                "action", op,
                "amount", amount,
                "region", envelope.getHeaders().getOrDefault("compliance_region", "N/A")
        );

        Map<String, Object> notifyPayload = Map.of(
                "paymentId", pk,
                "message", "Payment " + op + " processed: " + amount + " CNY"
        );

        return new ProcessingResult.Success(List.of(
                new Destination("kafka", "payment-sync-out", syncPayload),
                new Destination("kafka", "payment-audit-log", auditPayload),
                new Destination("kafka", "payment-notification", notifyPayload)
        ));
    }
}
