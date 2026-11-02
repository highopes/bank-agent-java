package com.demo.bankagent.observability;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Non-secret OpenTelemetry tuning (application.yml, bank-agent.observability.*). The user-facing switches and
 * endpoints live in galileo.properties and are resolved into {@link AoSettings}.
 */
@ConfigurationProperties(prefix = "bank-agent.observability")
public record ObservabilityProperties(
        double samplingRatio,
        Duration exportTimeout,
        Duration batchScheduleDelay,
        int maxExportBatchSize,
        int maxQueueSize,
        int maxAttributeLength,
        int maxContentChars,
        Duration apiTimeout,
        int apiMaxAttempts,
        String deploymentEnvironment) {

    public ObservabilityProperties {
        if (samplingRatio <= 0 || samplingRatio > 1) {
            samplingRatio = 1.0;
        }
        exportTimeout = exportTimeout == null ? Duration.ofSeconds(10) : exportTimeout;
        batchScheduleDelay = batchScheduleDelay == null ? Duration.ofSeconds(2) : batchScheduleDelay;
        maxExportBatchSize = maxExportBatchSize <= 0 ? 256 : maxExportBatchSize;
        maxQueueSize = maxQueueSize <= 0 ? 2048 : maxQueueSize;
        maxAttributeLength = maxAttributeLength <= 0 ? 16384 : maxAttributeLength;
        maxContentChars = maxContentChars <= 0 ? 2000 : maxContentChars;
        apiTimeout = apiTimeout == null ? Duration.ofSeconds(10) : apiTimeout;
        apiMaxAttempts = apiMaxAttempts <= 0 ? 3 : apiMaxAttempts;
        deploymentEnvironment = deploymentEnvironment == null || deploymentEnvironment.isBlank()
                ? "local-demo" : deploymentEnvironment;
    }
}
