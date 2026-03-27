package com.hsbc.pluse.routing;

import com.hsbc.pluse.model.RouteKey;
import com.hsbc.pluse.pipeline.PipelineDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class PipelineRegistry {

    private static final Logger log = LoggerFactory.getLogger(PipelineRegistry.class);
    private final List<PipelineDefinition> pipelines = new CopyOnWriteArrayList<>();

    public void register(PipelineDefinition definition) {
        pipelines.add(definition);
        log.info("Registered pipeline: routeKey={}, ordered={}", definition.routeKeyPattern(), definition.ordered());
    }

    public List<PipelineDefinition> resolve(RouteKey key) {
        return pipelines.stream()
                .filter(p -> key.matches(RouteKey.parse(p.routeKeyPattern())))
                .toList();
    }

    public List<PipelineDefinition> getAll() {
        return List.copyOf(pipelines);
    }
}
