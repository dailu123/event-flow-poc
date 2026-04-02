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

    @Value("${otel.traces.exporter.logging-enabled:false}")
    private boolean loggingExporterEnabled;

    @Value("${otel.traces.batch.schedule-delay-ms:200}")
    private long batchScheduleDelayMs;

    @Value("${otel.traces.batch.max-queue-size:65536}")
    private int batchMaxQueueSize;

    @Value("${otel.traces.batch.max-export-batch-size:2048}")
    private int batchMaxExportBatchSize;

    @Value("${otel.traces.batch.export-timeout-ms:30000}")
    private long batchExportTimeoutMs;

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

        var tracerProviderBuilder = SdkTracerProvider.builder()
                .addSpanProcessor(BatchSpanProcessor.builder(otlpExporter)
                        .setScheduleDelay(Duration.ofMillis(batchScheduleDelayMs))
                        .setMaxQueueSize(batchMaxQueueSize)
                        .setMaxExportBatchSize(batchMaxExportBatchSize)
                        .setExporterTimeout(Duration.ofMillis(batchExportTimeoutMs))
                        .build())
                .setResource(resource);

        if (loggingExporterEnabled) {
            tracerProviderBuilder.addSpanProcessor(SimpleSpanProcessor.create(LoggingSpanExporter.create()));
        }

        SdkTracerProvider tracerProvider = tracerProviderBuilder.build();

        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .buildAndRegisterGlobal();

        Runtime.getRuntime().addShutdownHook(new Thread(tracerProvider::close));
        return sdk;
    }
}
