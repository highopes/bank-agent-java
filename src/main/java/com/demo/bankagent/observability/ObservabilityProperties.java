package com.demo.bankagent.observability;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Non-secret tuning of the application's own Observe helpers (application.yml, bank-agent.observability.*).
 * Export, batching, sampling and the resource are NOT here: they are standard OTEL_* settings of the
 * OpenTelemetry Java agent (config/otel-javaagent.properties). The user-facing switches and endpoints live in
 * galileo.properties and are resolved into {@link AoSettings}.
 */
@ConfigurationProperties(prefix = "bank-agent.observability")
public record ObservabilityProperties(
        int maxContentChars,
        Duration apiTimeout,
        int apiMaxAttempts) {

    public ObservabilityProperties {
        maxContentChars = maxContentChars <= 0 ? 2000 : maxContentChars;
        apiTimeout = apiTimeout == null ? Duration.ofSeconds(10) : apiTimeout;
        apiMaxAttempts = apiMaxAttempts <= 0 ? 3 : apiMaxAttempts;
    }
}
