package com.hsbc.pluse.test;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Composite test annotation: loads Spring context with mock inbound/outbound ports,
 * disabling real Kafka connectivity.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest
@Import(EventFlowTestConfiguration.class)
@TestPropertySource(properties = {
        "eventflow.kafka.topics=",   // disable Kafka inbound
        "eventflow.enabled=true"
})
public @interface EventProcessingTest {
}
