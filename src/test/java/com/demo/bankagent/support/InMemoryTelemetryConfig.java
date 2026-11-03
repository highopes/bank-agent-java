package com.demo.bankagent.support;

import com.demo.bankagent.observability.BankTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Records spans in memory instead of exporting them (no network); used by the instrumentation integration tests. */
@TestConfiguration(proxyBeanMethods = false)
public class InMemoryTelemetryConfig {

    @Bean
    public InMemorySpanExporter inMemorySpanExporter() {
        return InMemorySpanExporter.create();
    }

    @Bean
    @Primary
    public BankTelemetry testTelemetry(InMemorySpanExporter exporter) {
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().setTracerProvider(provider).build();
        return new BankTelemetry(sdk, "test", true, new BankTelemetry.ExportStats(), provider);
    }
}
