package com.hsbc.pluse.sample.steps;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hsbc.pluse.model.Envelope;
import com.hsbc.pluse.pipeline.PipelineStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pure business step — ZERO framework/OTel dependency.
 * Enrichment: simulates calling an exchange rate API and adding
 * CNY/USD/HKD rates to the event. Adds ~50ms simulated latency.
 */
public class ExchangeRateEnrichStep implements PipelineStep {

    private static final Logger log = LoggerFactory.getLogger(ExchangeRateEnrichStep.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public Envelope process(Envelope envelope) {
        // Simulate external API call latency
        try { Thread.sleep(50); } catch (InterruptedException ignored) {}

        double amount = 0;
        if (envelope.getData().has("amount")) {
            amount = envelope.getData().get("amount").asDouble();
        }

        double usdRate = 7.25;
        double hkdRate = 0.93;

        ObjectNode enriched = (ObjectNode) envelope.getData().deepCopy();
        enriched.put("cny_amount", amount);
        enriched.put("usd_amount", Math.round(amount / usdRate * 100.0) / 100.0);
        enriched.put("hkd_amount", Math.round(amount / hkdRate * 100.0) / 100.0);
        enriched.put("exchange_rate_source", "PBOC");
        enriched.put("exchange_rate_time", System.currentTimeMillis());

        log.info("Enriched with exchange rates: CNY={}, USD={}, HKD={}",
                amount, Math.round(amount / usdRate * 100.0) / 100.0,
                Math.round(amount / hkdRate * 100.0) / 100.0);

        return envelope.withData(enriched);
    }
}
