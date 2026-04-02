package com.hsbc.pluse.sample.poc;

import com.hsbc.pluse.idempotent.IdempotentFilter;
import com.hsbc.pluse.model.Envelope;
import com.hsbc.pluse.pipeline.PipelineDefinition;
import com.hsbc.pluse.routing.PipelineRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.List;

/**
 * Configuration for POC pipelines.
 * Registers both Strategy A (direct) and Strategy B (stored) handlers for the same route key,
 * enabling fan-out processing where both handlers process the same event simultaneously.
 */
@Configuration
@EnableScheduling
public class PocConfig {

    public PocConfig(PipelineRegistry registry,
                     ObjectProvider<PocStoredHandler> storedHandlerProvider) {
        // Strategy A: Direct processing (auto-registered via @EventPipeline annotation)

        // Strategy B: Store-and-process (manual registration for same route key)
        PocStoredHandler storedHandler = storedHandlerProvider.getIfAvailable();
        if (storedHandler != null) {
            registry.register(new PipelineDefinition(
                "t_poc:*",
                false,
                "",
                "poc-B",
                storedHandler,
                List.of()
            ));
        }
    }

    /**
     * Override the default CaffeineIdempotentFilter with a no-op version.
     * Required for fan-out: the same event goes to multiple pipelines,
     * and the default filter would mark the second pipeline's copy as duplicate.
     */
    @Bean
    public IdempotentFilter idempotentFilter() {
        return new IdempotentFilter() {
            @Override
            public boolean isDuplicate(Envelope envelope) {
                return false; // Never mark as duplicate in POC
            }
        };
    }
}
