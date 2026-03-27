package com.hsbc.pluse.sample.steps;

import com.hsbc.pluse.model.Envelope;
import com.hsbc.pluse.pipeline.PipelineStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pure business step — ZERO framework/OTel dependency.
 * Anti-fraud check: simulates calling fraud detection service.
 * - amount > 50000: HIGH risk, FILTERED (returns null)
 * - amount > 10000: MEDIUM risk, adds warning header but passes
 * - otherwise: LOW risk, passes through
 * Adds ~30ms simulated latency.
 */
public class FraudCheckStep implements PipelineStep {

    private static final Logger log = LoggerFactory.getLogger(FraudCheckStep.class);

    @Override
    public Envelope process(Envelope envelope) {
        // Simulate fraud detection API latency
        try { Thread.sleep(30); } catch (InterruptedException ignored) {}

        double amount = 0;
        if (envelope.getData().has("amount")) {
            amount = envelope.getData().get("amount").asDouble();
        }
        String pk = envelope.getHeaders().getOrDefault("pk", "unknown");

        if (amount > 50000) {
            log.warn("FRAUD CHECK BLOCKED: pk={}, amount={}, risk=HIGH", pk, amount);
            return null; // Filter out this event!
        } else if (amount > 10000) {
            log.warn("FRAUD CHECK WARNING: pk={}, amount={}, risk=MEDIUM", pk, amount);
            return envelope.withHeader("fraud_risk", "MEDIUM")
                           .withHeader("fraud_flag", "REVIEW_REQUIRED");
        } else {
            log.info("Fraud check passed: pk={}, amount={}, risk=LOW", pk, amount);
            return envelope.withHeader("fraud_risk", "LOW");
        }
    }
}
