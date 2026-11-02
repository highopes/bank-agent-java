package com.demo.bankagent.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Resolves the Project / Agent Stream once at startup, only when OBSERVABILITY_ENABLED=true or
 * CONTROL_MODE=remote-api (never per request; zero network calls when both are off).
 *
 * <p>Failure semantics (task book 0.4.4): with CONTROL_MODE=remote-api a failure stops the application (the
 * Agent Control target ID is mandatory). With Observe only, the application starts, logs a clear warning and
 * keeps exporting spans routed by project/stream NAME (P0 showed the o11y ingest routes by name).
 */
@Component
public class AoTargetHolder {

    private static final Logger log = LoggerFactory.getLogger(AoTargetHolder.class);

    public enum State { DISABLED, RESOLVED, FAILED }

    private final AoSettings settings;
    private final AoTarget target;
    private final State state;
    private final String error;

    public AoTargetHolder(AoSettings settings, ObservabilityProperties tuning, ObjectMapper mapper) {
        this.settings = settings;
        if (!settings.active()) {
            this.target = null;
            this.state = State.DISABLED;
            this.error = null;
            return;
        }
        AoTarget resolved = null;
        String failure = null;
        try {
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            AoApiClient api = new AoApiClient(http, mapper, settings.apiUrl().value(), apiAuthHeader(settings),
                    settings.apiToken().value(), tuning.apiTimeout(), tuning.apiMaxAttempts());
            resolved = new AoTargetResolver(api, settings.profile())
                    .resolve(settings.projectName().value(), settings.agentStream().value());
            log.info("AO target resolved: project '{}' id={} ({}), agent stream '{}' id={} ({})",
                    resolved.projectName(), resolved.projectId(), resolved.projectCreated() ? "created" : "existing",
                    resolved.agentStreamName(), resolved.agentStreamId(),
                    resolved.agentStreamCreated() ? "created" : "existing");
        } catch (AoApiException e) {
            failure = e.getMessage() + (e.isAuthError() ? " - the credential lacks permission (o11y needs the "
                    + "agent_observability_admin role and API scope), or ask an administrator to create project '"
                    + settings.projectName().value() + "' / agent stream '" + settings.agentStream().value() + "'."
                    : "");
        } catch (RuntimeException e) {
            failure = "Unexpected error while resolving the AO project/agent stream: " + e.getClass().getSimpleName();
        }
        if (resolved == null) {
            if (settings.controlEnabled()) {
                throw new AoConfigurationException("CONTROL_MODE=remote-api requires the Agent Stream ID, but the "
                        + "project/agent stream lookup failed: " + failure);
            }
            log.warn("AO project/agent stream lookup failed: {}. The application continues; spans are still exported "
                    + "routed by name (project='{}', stream='{}'), but Observe is reported as degraded.", failure,
                    settings.projectName().value(), settings.agentStream().value());
        }
        this.target = resolved;
        this.state = resolved == null ? State.FAILED : State.RESOLVED;
        this.error = failure;
    }

    /** The AO REST API header name: X-SF-Token (o11y) or Galileo-API-Key (galileo). */
    static String apiAuthHeader(AoSettings settings) {
        return settings.profile() == AoBackendProfile.O11Y ? "X-SF-Token" : "Galileo-API-Key";
    }

    public Optional<AoTarget> target() {
        return Optional.ofNullable(target);
    }

    public State state() {
        return state;
    }

    public Optional<String> error() {
        return Optional.ofNullable(error);
    }

    /** Console page of the agent stream, or empty when not resolved. */
    public Optional<String> consoleStreamUrl() {
        if (target == null || settings.consoleUrl().value() == null) {
            return Optional.empty();
        }
        String console = settings.consoleUrl().value();
        return Optional.of(settings.profile() == AoBackendProfile.O11Y
                ? console + "/#/agent-obs/project/" + target.projectId() + "/agent-streams/" + target.agentStreamId()
                : console + "/project/" + target.projectId() + "/log-streams/" + target.agentStreamId());
    }
}
