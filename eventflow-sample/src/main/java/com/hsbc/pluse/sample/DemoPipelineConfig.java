package com.hsbc.pluse.sample;

import com.hsbc.pluse.pipeline.PipelineDefinition;
import com.hsbc.pluse.routing.PipelineRegistry;
import com.hsbc.pluse.sample.steps.ComplianceAuditStep;
import com.hsbc.pluse.sample.steps.ExchangeRateEnrichStep;
import com.hsbc.pluse.sample.steps.FraudCheckStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;
import java.util.List;

/**
 * Registers the t_payment pipeline with full pre-step chain:
 *   ExchangeRateEnrichStep → FraudCheckStep → ComplianceAuditStep → PaymentCdcHandler
 *
 * Note: No OTel dependency — all tracing is automatic at the framework Dispatcher level.
 */
@Configuration
public class DemoPipelineConfig {

    private static final Logger log = LoggerFactory.getLogger(DemoPipelineConfig.class);

    private final PipelineRegistry registry;

    public DemoPipelineConfig(PipelineRegistry registry) {
        this.registry = registry;
    }

    @PostConstruct
    public void registerPaymentPipeline() {
        PipelineDefinition paymentPipeline = new PipelineDefinition(
                "t_payment:*",
                true,
                "",
                new PaymentCdcHandler(),
                List.of(
                        new ExchangeRateEnrichStep(),
                        new FraudCheckStep(),
                        new ComplianceAuditStep()
                )
        );
        registry.register(paymentPipeline);
        log.info("Registered t_payment pipeline with 3 pre-steps: ExchangeRateEnrich → FraudCheck → ComplianceAudit");
    }
}
