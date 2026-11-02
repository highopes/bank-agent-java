package com.demo.bankagent.observability;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Raw values of the private galileo.properties file (or same-named environment variables), exactly as the user
 * wrote them. Every field is a String because an empty value must mean "not set" (Spring's ${KEY:default} only
 * applies the default when the key is missing, not when it is empty). {@link AoEndpointResolver} turns these
 * into the final {@link AoSettings}; business code never reads this record.
 */
@ConfigurationProperties(prefix = "bank-agent.ao")
public record AoRawProperties(
        String observabilityEnabled,
        String controlMode,
        String demoTraceContent,
        String realm,
        String o11yToken,
        String consoleUrl,
        String apiKey,
        String projectName,
        String agentStream,
        String backendProfile,
        String apiUrl,
        String o11yApiToken,
        String otlpEndpoint,
        String otlpAuthHeader,
        String serviceName,
        String agentControlUrl,
        String agentControlEvaluationPath,
        String agentControlApiKeyHeader,
        String agentControlApiKey,
        String agentControlAgentName,
        String agentControlTargetType,
        String agentControlRuntimeAuthMode,
        String agentControlRuntimeTokenHeader,
        String controlReadFailureMode,
        String controlWriteFailureMode,
        String maxControlSteerRetries) {

    /** Builds the record from the user-facing key names (used by tests and diagnostics). */
    public static AoRawProperties fromKeys(Map<String, String> keys) {
        return new AoRawProperties(
                keys.get("OBSERVABILITY_ENABLED"), keys.get("CONTROL_MODE"), keys.get("DEMO_TRACE_CONTENT"),
                keys.get("AO_REALM"), keys.get("AO_O11Y_TOKEN"), keys.get("AO_CONSOLE_URL"), keys.get("AO_API_KEY"),
                keys.get("AO_PROJECT_NAME"), keys.get("AO_AGENT_STREAM"), keys.get("AO_BACKEND_PROFILE"),
                keys.get("AO_API_URL"), keys.get("AO_O11Y_API_TOKEN"), keys.get("AO_OTLP_ENDPOINT"),
                keys.get("AO_OTLP_AUTH_HEADER"), keys.get("OTEL_SERVICE_NAME"), keys.get("AGENT_CONTROL_URL"),
                keys.get("AGENT_CONTROL_EVALUATION_PATH"), keys.get("AGENT_CONTROL_API_KEY_HEADER"),
                keys.get("AGENT_CONTROL_API_KEY"), keys.get("AGENT_CONTROL_AGENT_NAME"),
                keys.get("AGENT_CONTROL_TARGET_TYPE"), keys.get("AGENT_CONTROL_RUNTIME_AUTH_MODE"),
                keys.get("AGENT_CONTROL_RUNTIME_TOKEN_HEADER"), keys.get("CONTROL_READ_FAILURE_MODE"),
                keys.get("CONTROL_WRITE_FAILURE_MODE"), keys.get("MAX_CONTROL_STEER_RETRIES"));
    }

    @Override
    public String toString() {
        // Never print credentials, not even partially.
        return "AoRawProperties[backendProfile=" + backendProfile + ", realm=" + realm + ", consoleUrl=" + consoleUrl
                + ", o11yToken=" + secretState(o11yToken) + ", apiKey=" + secretState(apiKey)
                + ", o11yApiToken=" + secretState(o11yApiToken) + ", agentControlApiKey="
                + secretState(agentControlApiKey) + "]";
    }

    private static String secretState(String value) {
        return value == null || value.isBlank() ? "<unset>" : "<set>";
    }
}
