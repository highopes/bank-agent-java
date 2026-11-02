package com.demo.bankagent.control;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Non-secret Agent Control client tuning (application.yml, bank-agent.control.*). Endpoint, credential and
 * failure modes come from galileo.properties via {@code AoSettings}.
 */
@ConfigurationProperties(prefix = "bank-agent.control")
public record ControlProperties(
        Duration connectTimeout,
        Duration requestTimeout,
        int evaluationAttempts,
        Duration tokenRefreshMargin,
        Duration controlsRefreshInterval,
        boolean prefilter,
        int startupAttempts) {

    public ControlProperties {
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(3) : connectTimeout;
        requestTimeout = requestTimeout == null ? Duration.ofSeconds(5) : requestTimeout;
        evaluationAttempts = evaluationAttempts <= 0 ? 2 : evaluationAttempts;
        tokenRefreshMargin = tokenRefreshMargin == null ? Duration.ofSeconds(30) : tokenRefreshMargin;
        controlsRefreshInterval = controlsRefreshInterval == null ? Duration.ofSeconds(5) : controlsRefreshInterval;
        startupAttempts = startupAttempts <= 0 ? 3 : startupAttempts;
    }
}
