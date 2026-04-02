package com.hsbc.pluse.pipeline;

import java.util.List;

public record PipelineDefinition(
        String routeKeyPattern,
        boolean ordered,
        String orderKeyExpr,
        String executionGroup,
        EventHandler handler,
        List<PipelineStep> preSteps
) {
    public PipelineDefinition {
        executionGroup = executionGroup != null ? executionGroup : "";
        preSteps = preSteps != null ? List.copyOf(preSteps) : List.of();
    }

    public PipelineDefinition(
            String routeKeyPattern,
            boolean ordered,
            String orderKeyExpr,
            EventHandler handler,
            List<PipelineStep> preSteps
    ) {
        this(routeKeyPattern, ordered, orderKeyExpr, "", handler, preSteps);
    }
}
