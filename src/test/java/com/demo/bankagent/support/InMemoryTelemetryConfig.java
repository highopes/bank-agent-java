package com.demo.bankagent.support;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Stands in for the OpenTelemetry Java agent inside the test JVM: an OpenTelemetry SDK that records spans in memory
 * (no network). The application code is unchanged - it only sees an OpenTelemetry instance, like the one the agent
 * installs. The real agent is exercised by OtelJavaAgentIT.
 */
@TestConfiguration(proxyBeanMethods = false)
public class InMemoryTelemetryConfig {

    @Bean
    public InMemorySpanExporter inMemorySpanExporter() {
        return InMemorySpanExporter.create();
    }

    @Bean
    @Primary
    public OpenTelemetry testOpenTelemetry(InMemorySpanExporter exporter) {
        return OpenTelemetrySdk.builder()
                .setTracerProvider(SdkTracerProvider.builder()
                        .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                        .build())
                .build();
    }
}
