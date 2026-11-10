package com.demo.bankagent.observability;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Observe wiring: the application only uses the OpenTelemetry API. With the official OpenTelemetry Java agent
 * attached (scripts/run.sh --observe), {@link GlobalOpenTelemetry} is the agent's SDK, configured by standard
 * OTEL_* settings (endpoint, headers, resource, sampling, batching); without the agent it is a no-op.
 */
@Configuration(proxyBeanMethods = false)
public class OtelConfig {

    @Bean
    public OpenTelemetry openTelemetry() {
        return GlobalOpenTelemetry.get();
    }
}
