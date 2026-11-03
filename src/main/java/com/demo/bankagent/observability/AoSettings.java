package com.demo.bankagent.observability;

import com.demo.bankagent.control.ControlFailureMode;
import com.demo.bankagent.control.ControlMode;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * Final, derived observability and Agent Control settings. Built only by {@link AoEndpointResolver}; business
 * code reads final values from here and never re-derives URLs. Secrets are wrapped in {@link Secret} so that
 * toString()/logs can only ever show "set" or "missing".
 */
public record AoSettings(
        boolean observabilityEnabled,
        ControlMode controlMode,
        boolean demoTraceContent,
        AoBackendProfile profile,
        String profileSource,
        String realm,
        Value<String> consoleUrl,
        Value<String> apiUrl,
        Secret apiToken,
        Value<String> otlpEndpoint,
        Value<String> otlpAuthHeader,
        Secret otlpAuthValue,
        Value<String> serviceName,
        Value<String> agentControlUrl,
        Value<String> agentControlEvaluationPath,
        Value<String> agentControlApiKeyHeader,
        Secret agentControlApiKey,
        Value<String> agentControlAgentName,
        Value<String> agentControlTargetType,
        Value<String> runtimeAuthMode,
        Value<String> runtimeTokenHeader,
        Value<ControlFailureMode> readFailureMode,
        Value<ControlFailureMode> writeFailureMode,
        Value<Integer> maxSteerRetries,
        Value<String> projectName,
        Value<String> agentStream,
        List<String> problems) {

    /** Where a final value came from. */
    public enum Source {
        /** Derived from the active profile by the documented rule (the key was empty). */
        DERIVED,
        /** Explicitly set by the user (overrides the rule). */
        OVERRIDE,
        /** Built-in default (no rule involved). */
        DEFAULT
    }

    public record Value<T>(T value, Source source) {

        public static <T> Value<T> derived(T value) {
            return new Value<>(value, Source.DERIVED);
        }

        public static <T> Value<T> override(T value) {
            return new Value<>(value, Source.OVERRIDE);
        }

        public static <T> Value<T> defaulted(T value) {
            return new Value<>(value, Source.DEFAULT);
        }

        @Override
        public String toString() {
            return value + " [" + source.name().toLowerCase() + "]";
        }
    }

    /** A credential. Only its presence and origin are ever printed. */
    public record Secret(String value, String origin, Source source) {

        public static Secret missing(String origin) {
            return new Secret(null, origin, Source.DERIVED);
        }

        public boolean isSet() {
            return value != null && !value.isBlank();
        }

        @Override
        public String toString() {
            return (isSet() ? "<set>" : "<missing>") + " (" + origin + ") [" + source.name().toLowerCase() + "]";
        }
    }

    public AoSettings {
        problems = List.copyOf(problems);
    }

    /** True when any Galileo feature that talks to the backend is switched on. */
    public boolean active() {
        return observabilityEnabled || controlMode == ControlMode.REMOTE_API;
    }

    public boolean controlEnabled() {
        return controlMode == ControlMode.REMOTE_API;
    }

    /** Resource attribute keys that route spans by name (SDK exporter/config.py vs galileo/otel.py). */
    public String projectRoutingAttribute() {
        return profile == AoBackendProfile.O11Y ? "splunk_ao.project.name" : "galileo.project.name";
    }

    public String streamRoutingAttribute() {
        return profile == AoBackendProfile.O11Y ? "splunk_ao.logstream.name" : "galileo.logstream.name";
    }

    /** Human-readable, secret-free description for the startup log and the preflight script. */
    public List<String> describe() {
        List<String> lines = new ArrayList<>();
        lines.add("OBSERVABILITY_ENABLED=" + observabilityEnabled + ", CONTROL_MODE=" + controlMode.key()
                + ", DEMO_TRACE_CONTENT=" + demoTraceContent);
        lines.add("profile=" + profile.key() + " (" + profileSource + ")"
                + (realm == null ? "" : ", realm=" + realm));
        lines.add("AO_CONSOLE_URL=" + consoleUrl);
        lines.add("AO_API_URL=" + apiUrl);
        lines.add("AO API credential=" + apiToken);
        lines.add("AO_OTLP_ENDPOINT=" + otlpEndpoint);
        lines.add("AO_OTLP_AUTH_HEADER=" + otlpAuthHeader + ", credential=" + otlpAuthValue);
        lines.add("OTEL_SERVICE_NAME=" + serviceName);
        lines.add("AO_PROJECT_NAME=" + projectName + ", AO_AGENT_STREAM=" + agentStream);
        lines.add("AGENT_CONTROL_URL=" + agentControlUrl);
        lines.add("AGENT_CONTROL_EVALUATION_PATH=" + agentControlEvaluationPath);
        lines.add("AGENT_CONTROL_API_KEY_HEADER=" + agentControlApiKeyHeader + ", credential=" + agentControlApiKey);
        lines.add("AGENT_CONTROL_AGENT_NAME=" + agentControlAgentName + ", AGENT_CONTROL_TARGET_TYPE="
                + agentControlTargetType);
        lines.add("AGENT_CONTROL_RUNTIME_AUTH_MODE=" + runtimeAuthMode + ", AGENT_CONTROL_RUNTIME_TOKEN_HEADER="
                + runtimeTokenHeader);
        lines.add("CONTROL_READ_FAILURE_MODE=" + readFailureMode.value().key() + " [" + readFailureMode.source()
                .name().toLowerCase() + "], CONTROL_WRITE_FAILURE_MODE=" + writeFailureMode.value().key() + " ["
                + writeFailureMode.source().name().toLowerCase() + "], MAX_CONTROL_STEER_RETRIES=" + maxSteerRetries);
        problems.forEach(p -> lines.add("problem: " + p));
        return lines;
    }

    /** Host part of a URL for logs ("ingest.au0.observability.splunkcloud.com"). */
    public static String host(String url) {
        try {
            return url == null ? "-" : URI.create(url).getHost();
        } catch (IllegalArgumentException e) {
            return "-";
        }
    }
}
