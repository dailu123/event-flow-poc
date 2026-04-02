package com.hsbc.pluse.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface EventPipeline {
    String routeKey();
    boolean ordered() default false;
    String orderKeyExpr() default "";
    String executionGroup() default "";
}
