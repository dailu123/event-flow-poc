package com.hsbc.pluse.sample.steps;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hsbc.pluse.model.Envelope;
import com.hsbc.pluse.pipeline.PipelineStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pure business step — ZERO framework/OTel dependency.
 * Compliance audit: stamps the event with audit metadata.
 * Simulates compliance system lookup (~20ms).
 */
public class ComplianceAuditStep implements PipelineStep {

    private static final Logger log = LoggerFactory.getLogger(ComplianceAuditStep.class);

    @Override
    public Envelope process(Envelope envelope) {
        // Simulate compliance check latency
        try { Thread.sleep(20); } catch (InterruptedException ignored) {}

        String region = "APAC";
        String complianceCode = "HK-AML-2024";

        ObjectNode audited = (ObjectNode) envelope.getData().deepCopy();
        audited.put("compliance_region", region);
        audited.put("compliance_code", complianceCode);
        audited.put("audit_timestamp", System.currentTimeMillis());
        audited.put("auditor", "eventflow-compliance-engine");

        log.info("Compliance audit stamped: region={}, code={}", region, complianceCode);

        return envelope.withData(audited)
                       .withHeader("compliance_region", region)
                       .withHeader("compliance_code", complianceCode);
    }
}
