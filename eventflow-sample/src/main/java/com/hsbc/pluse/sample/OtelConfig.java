package com.hsbc.pluse.sample;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.exporter.logging.LoggingSpanExporter;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.springframework.beans.factory.annotation.Value;

import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configures OpenTelemetry SDK with dual exporters:
 * - LoggingSpanExporter: prints spans to console
 * - OtlpGrpcSpanExporter: sends spans to Jaeger (http://localhost:4317)
 */
@Configuration
public class OtelConfig {

    @Value("${otel.service.name:eventflow-sample}")
    private String serviceName;

    @Value("${otel.exporter.otlp.endpoint:http://localhost:4317}")
    private String otlpEndpoint;

    @Bean
    public OpenTelemetry openTelemetry() {
        Resource resource = Resource.getDefault()
                .merge(Resource.create(Attributes.of(
                        AttributeKey.stringKey("service.name"), serviceName
                )));

        // OTLP exporter → Jaeger
        OtlpGrpcSpanExporter otlpExporter = OtlpGrpcSpanExporter.builder()
                .setEndpoint(otlpEndpoint)
                .build();

        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(BatchSpanProcessor.builder(otlpExporter)
                        .setScheduleDelay(Duration.ofSeconds(1))
                        .build())
                .addSpanProcessor(SimpleSpanProcessor.create(LoggingSpanExporter.create()))
                .setResource(resource)
                .build();

        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .buildAndRegisterGlobal();

        Runtime.getRuntime().addShutdownHook(new Thread(tracerProvider::close));
        return sdk;
    }
}
