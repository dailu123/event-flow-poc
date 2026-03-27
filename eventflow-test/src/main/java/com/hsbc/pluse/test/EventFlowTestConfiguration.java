package com.hsbc.pluse.test;

import com.hsbc.pluse.routing.HeaderBasedRouter;
import com.hsbc.pluse.routing.PipelineRegistry;
import com.hsbc.pluse.routing.Router;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Test auto-configuration: replaces real Kafka adapters with mock/capture implementations.
 */
@TestConfiguration
public class EventFlowTestConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public PipelineRegistry pipelineRegistry() {
        return new PipelineRegistry();
    }

    @Bean
    @ConditionalOnMissingBean
    public Router router() {
        return new HeaderBasedRouter();
    }

    @Bean
    @Primary
    public MockInboundPort mockInboundPort(PipelineRegistry registry, Router router) {
        return new MockInboundPort(registry, router);
    }

    @Bean
    @Primary
    public CaptureOutboundPort captureOutboundPort() {
        return new CaptureOutboundPort();
    }
}
