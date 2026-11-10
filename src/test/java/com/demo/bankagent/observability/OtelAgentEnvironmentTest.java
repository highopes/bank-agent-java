package com.demo.bankagent.observability;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The standard OTEL_* settings that scripts/run.sh --observe hands to the OpenTelemetry Java agent are derived
 * from galileo.properties by the same rules as the rest of the application (AoEndpointResolver).
 */
class OtelAgentEnvironmentTest {

    private static AoSettings resolve(Map<String, String> keys) {
        return AoEndpointResolver.resolve(AoRawProperties.fromKeys(keys));
    }

    /** Parses "k1=v1,k2=v2" exactly like the OTel SDK does (split, then URL-decode the value). */
    private static Map<String, String> otelMap(String value) {
        Map<String, String> map = new LinkedHashMap<>();
        for (String pair : value.split(",")) {
            int eq = pair.indexOf('=');
            map.put(pair.substring(0, eq).trim(),
                    URLDecoder.decode(pair.substring(eq + 1).trim(), StandardCharsets.UTF_8));
        }
        return map;
    }

    @Test
    void o11yProfileUsesTheAoTraceEndpointTokenAndLogstreamRouting() {
        Map<String, String> env = resolve(Map.of("OBSERVABILITY_ENABLED", "true", "AO_REALM", "au0",
                "AO_O11Y_TOKEN", "tok-SECRET")).otelAgentEnvironment();

        assertThat(env).containsOnlyKeys("OTEL_SERVICE_NAME", "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT",
                "OTEL_EXPORTER_OTLP_TRACES_HEADERS", "OTEL_RESOURCE_ATTRIBUTES");
        assertThat(env.get("OTEL_SERVICE_NAME")).isEqualTo("bank-agent-java");
        // The AO ingest path, not the generic collector path /v1/traces (signal-specific endpoints are used as-is).
        assertThat(env.get("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT"))
                .isEqualTo("https://ingest.au0.observability.splunkcloud.com/v2/trace/otlp");
        assertThat(otelMap(env.get("OTEL_EXPORTER_OTLP_TRACES_HEADERS"))).containsExactly(
                Map.entry("X-SF-Token", "tok-SECRET"), Map.entry("project", "bank-agent-java"),
                Map.entry("logstream", "bank-java-demo"));
        assertThat(otelMap(env.get("OTEL_RESOURCE_ATTRIBUTES"))).containsEntry("splunk_ao.project.name",
                "bank-agent-java").containsEntry("splunk_ao.logstream.name", "bank-java-demo")
                .containsEntry("deployment.environment.name", "local-demo")
                .doesNotContainKey("splunk_ao.agentstream.name");
    }

    @Test
    void overridesAndSpecialCharactersSurviveTheOtelEncoding() {
        Map<String, String> env = resolve(Map.of("OBSERVABILITY_ENABLED", "true", "AO_REALM", "us1",
                "AO_O11Y_TOKEN", "a,b=c d", "AO_PROJECT_NAME", "my project", "AO_AGENT_STREAM", "stream,1",
                "AO_OTLP_ENDPOINT", "http://127.0.0.1:4318/v2/trace/otlp/", "OTEL_SERVICE_NAME", "svc"))
                .otelAgentEnvironment();

        assertThat(env.get("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT")).isEqualTo("http://127.0.0.1:4318/v2/trace/otlp");
        assertThat(env.get("OTEL_SERVICE_NAME")).isEqualTo("svc");
        assertThat(otelMap(env.get("OTEL_EXPORTER_OTLP_TRACES_HEADERS"))).containsEntry("X-SF-Token", "a,b=c d")
                .containsEntry("project", "my project").containsEntry("logstream", "stream,1");
    }

    @Test
    void galileoProfileUsesItsOwnAuthHeaderAndRoutingAttributes() {
        Map<String, String> env = resolve(Map.of("OBSERVABILITY_ENABLED", "true", "AO_API_KEY", "gk-SECRET"))
                .otelAgentEnvironment();

        assertThat(env.get("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT")).isEqualTo("https://api.galileo.ai/otel/traces");
        assertThat(otelMap(env.get("OTEL_EXPORTER_OTLP_TRACES_HEADERS"))).containsEntry("Galileo-API-Key", "gk-SECRET");
        assertThat(otelMap(env.get("OTEL_RESOURCE_ATTRIBUTES"))).containsKeys("galileo.project.name",
                "galileo.logstream.name");
    }

    @Test
    void describeNeverShowsTheCredential() {
        AoSettings settings = resolve(Map.of("OBSERVABILITY_ENABLED", "true", "AO_REALM", "au0",
                "AO_O11Y_TOKEN", "tok-SECRET"));

        assertThat(String.join("\n", settings.describe())).doesNotContain("tok-SECRET")
                .contains("OTEL_EXPORTER_OTLP_TRACES_HEADERS=X-SF-Token=<set>,project=bank-agent-java");
    }
}
