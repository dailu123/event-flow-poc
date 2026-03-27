package com.hsbc.pluse.boot;

import com.hsbc.pluse.annotation.EventPipeline;
import com.hsbc.pluse.pipeline.EventHandler;
import com.hsbc.pluse.pipeline.PipelineDefinition;
import com.hsbc.pluse.routing.PipelineRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;
import java.util.Collections;
import java.util.Map;

/**
 * Scans all beans annotated with @EventPipeline and registers them into PipelineRegistry.
 * Instantiated via EventFlowAutoConfiguration (not via component scan).
 */
public class EventFlowRegistrar implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(EventFlowRegistrar.class);

    private final ApplicationContext ctx;
    private final PipelineRegistry registry;

    public EventFlowRegistrar(ApplicationContext ctx, PipelineRegistry registry) {
        this.ctx = ctx;
        this.registry = registry;
    }

    @Override
    public void afterSingletonsInstantiated() {
        Map<String, Object> beans = ctx.getBeansWithAnnotation(EventPipeline.class);
        for (Map.Entry<String, Object> entry : beans.entrySet()) {
            Object bean = entry.getValue();
            EventPipeline ann = bean.getClass().getAnnotation(EventPipeline.class);
            if (ann == null) continue;

            if (bean instanceof EventHandler handler) {
                registry.register(new PipelineDefinition(
                        ann.routeKey(),
                        ann.ordered(),
                        ann.orderKeyExpr(),
                        handler,
                        Collections.emptyList()
                ));
                log.info("Auto-registered @EventPipeline: bean={}, routeKey={}, ordered={}",
                        entry.getKey(), ann.routeKey(), ann.ordered());
            } else {
                log.warn("Bean '{}' annotated with @EventPipeline but does not implement EventHandler, skipping",
                        entry.getKey());
            }
        }
    }
}
