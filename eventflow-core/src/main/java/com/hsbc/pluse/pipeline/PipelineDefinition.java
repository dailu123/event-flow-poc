package com.hsbc.pluse.pipeline;

import java.util.List;

public record PipelineDefinition(
        String routeKeyPattern,
        boolean ordered,
        String orderKeyExpr,
        EventHandler handler,
        List<PipelineStep> preSteps
) {
    public PipelineDefinition {
        preSteps = preSteps != null ? List.copyOf(preSteps) : List.of();
    }
}
