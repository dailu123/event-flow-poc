package com.hsbc.pluse.observe;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.slf4j.MDC;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Convenience wrapper around OpenTelemetry tracing for EventFlow pipeline spans.
 * Supports root spans, child spans, and spans with custom attributes.
 */
public class EventFlowTracer {

    private final Tracer tracer;

    public EventFlowTracer(OpenTelemetry openTelemetry) {
        this.tracer = openTelemetry.getTracer("eventflow", "1.0.0");
    }

    /**
     * Creates a CONSUMER root span with route_key and event_id attributes.
     */
    public <T> T traced(String spanName, String routeKey, String eventId, Supplier<T> work) {
        Span span = tracer.spanBuilder(spanName)
                .setSpanKind(SpanKind.CONSUMER)
                .setAttribute("eventflow.route_key", routeKey)
                .setAttribute("eventflow.event_id", eventId)
                .startSpan();
        return executeInSpan(span, work);
    }

    public void traced(String spanName, String routeKey, String eventId, Runnable work) {
        traced(spanName, routeKey, eventId, () -> { work.run(); return null; });
    }

    /**
     * Creates a child span under an explicit parent context (for cross-thread propagation).
     */
    public <T> T tracedChild(String spanName, Context parentContext, Supplier<T> work) {
        Span span = tracer.spanBuilder(spanName)
                .setParent(parentContext)
                .setSpanKind(SpanKind.INTERNAL)
                .startSpan();
        return executeInSpan(span, work);
    }

    /**
     * Creates a child span under an explicit parent with custom attributes.
     */
    public <T> T tracedChild(String spanName, Context parentContext,
                              Map<String, String> attributes, Supplier<T> work) {
        var builder = tracer.spanBuilder(spanName)
                .setParent(parentContext)
                .setSpanKind(SpanKind.INTERNAL);
        attributes.forEach(builder::setAttribute);
        Span span = builder.startSpan();
        return executeInSpan(span, work);
    }

    /**
     * Creates an INTERNAL child span under current active context.
     */
    public <T> T tracedInternal(String spanName, Supplier<T> work) {
        Span span = tracer.spanBuilder(spanName)
                .setSpanKind(SpanKind.INTERNAL)
                .startSpan();
        return executeInSpan(span, work);
    }

    /**
     * Creates an INTERNAL child span with custom string attributes.
     */
    public <T> T tracedInternal(String spanName, Map<String, String> attributes, Supplier<T> work) {
        var builder = tracer.spanBuilder(spanName)
                .setSpanKind(SpanKind.INTERNAL);
        attributes.forEach(builder::setAttribute);
        Span span = builder.startSpan();
        return executeInSpan(span, work);
    }

    /**
     * Creates a CONSUMER span and reconnects to a previous phase span when IDs are available.
     * For cross-phase processing (e.g. store -> scheduler), we set remote parent to keep one full trace tree.
     * A link is also kept for explicit phase correlation in tracing UIs.
     */
    public <T> T tracedWithLink(String spanName, String routeKey, String eventId,
                                 String linkedTraceId, String linkedSpanId, Supplier<T> work) {
        var builder = tracer.spanBuilder(spanName)
                .setSpanKind(SpanKind.CONSUMER)
                .setAttribute("eventflow.route_key", routeKey)
                .setAttribute("eventflow.event_id", eventId);

        if (linkedTraceId != null && linkedSpanId != null) {
            SpanContext linkedContext = SpanContext.createFromRemoteParent(
                    linkedTraceId, linkedSpanId, TraceFlags.getSampled(), TraceState.getDefault());
            // Reattach to original phase span so both phases appear in one end-to-end trace.
            builder.setParent(Context.root().with(Span.wrap(linkedContext)));
            builder.setAttribute("eventflow.reconnected_parent", true);
            // Keep explicit link semantics for UI correlation/debugging.
            builder.addLink(linkedContext);
        }

        Span span = builder.startSpan();
        return executeInSpan(span, work);
    }

    /**
     * Add an event (log annotation) to the current active span.
     */
    public void addEvent(String eventName) {
        Span.current().addEvent(eventName);
    }

    /**
     * Add an event with attributes to the current active span.
     */
    public void addEvent(String eventName, Map<String, String> attributes) {
        var attrBuilder = io.opentelemetry.api.common.Attributes.builder();
        attributes.forEach((k, v) -> attrBuilder.put(AttributeKey.stringKey(k), v));
        Span.current().addEvent(eventName, attrBuilder.build());
    }

    /**
     * Set attribute on the current active span.
     */
    public void setAttribute(String key, String value) {
        Span.current().setAttribute(key, value);
    }

    /**
     * Set attribute on the current active span.
     */
    public void setAttribute(String key, long value) {
        Span.current().setAttribute(key, value);
    }

    /**
     * Capture current context for cross-thread propagation.
     */
    public Context currentContext() {
        return Context.current();
    }

    private <T> T executeInSpan(Span span, Supplier<T> work) {
        try (Scope scope = span.makeCurrent()) {
            MDC.put("traceId", span.getSpanContext().getTraceId());
            MDC.put("spanId", span.getSpanContext().getSpanId());
            return work.get();
        } catch (Exception e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw e;
        } finally {
            MDC.remove("traceId");
            MDC.remove("spanId");
            span.end();
        }
    }
}
