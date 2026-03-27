package com.hsbc.pluse.test;

import com.hsbc.pluse.model.Envelope;
import com.hsbc.pluse.model.ProcessingResult;
import com.hsbc.pluse.model.RouteKey;
import com.hsbc.pluse.pipeline.PipelineDefinition;
import com.hsbc.pluse.pipeline.PipelineStep;
import com.hsbc.pluse.routing.PipelineRegistry;
import com.hsbc.pluse.routing.Router;

import java.util.List;

/**
 * Test-only inbound: directly injects Envelopes and synchronously executes pipelines.
 */
public class MockInboundPort {

    private final PipelineRegistry registry;
    private final Router router;

    public MockInboundPort(PipelineRegistry registry, Router router) {
        this.registry = registry;
        this.router = router;
    }

    public ProcessingResult send(Envelope envelope) {
        RouteKey key = router.route(envelope);
        List<PipelineDefinition> pipelines = registry.resolve(key);

        if (pipelines.isEmpty()) {
            throw new IllegalStateException("No pipeline matched for routeKey: " + key);
        }

        // Execute first matching pipeline synchronously (no offset tracking in test mode)
        PipelineDefinition pipeline = pipelines.get(0);

        Envelope current = envelope;
        for (PipelineStep step : pipeline.preSteps()) {
            current = step.process(current);
            if (current == null) {
                return new ProcessingResult.Filtered("filtered in test at " + step.getClass().getSimpleName());
            }
        }

        return pipeline.handler().handle(current);
    }

    /**
     * Send to all matched pipelines and return all results.
     */
    public List<ProcessingResult> sendToAll(Envelope envelope) {
        RouteKey key = router.route(envelope);
        List<PipelineDefinition> pipelines = registry.resolve(key);

        return pipelines.stream().map(pipeline -> {
            Envelope current = envelope.withData(envelope.getData().deepCopy());
            for (PipelineStep step : pipeline.preSteps()) {
                current = step.process(current);
                if (current == null) {
                    return (ProcessingResult) new ProcessingResult.Filtered("filtered");
                }
            }
            return pipeline.handler().handle(current);
        }).toList();
    }
}
