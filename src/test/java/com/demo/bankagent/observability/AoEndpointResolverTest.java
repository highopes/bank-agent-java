package com.demo.bankagent.observability;

import com.demo.bankagent.control.ControlFailureMode;
import com.demo.bankagent.control.ControlMode;
import com.demo.bankagent.observability.AoSettings.Source;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Derivation rules of task book 0.4.3 for the three backends, overrides, validation and secret hygiene. */
class AoEndpointResolverTest {

    private static final String O11Y_TOKEN = "o11y-token-SECRET-123";
    private static final String GALILEO_KEY = "galileo-key-SECRET-456";

    private static AoSettings resolve(String... keyValues) {
        Map<String, String> keys = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            keys.put(keyValues[i], keyValues[i + 1]);
        }
        return AoEndpointResolver.resolve(AoRawProperties.fromKeys(keys));
    }

    @Test
    void optionAOnlyRealmAndTokenDerivesEverything() {
        AoSettings s = resolve("AO_REALM", "au0", "AO_O11Y_TOKEN", O11Y_TOKEN, "OBSERVABILITY_ENABLED", "true");

        assertThat(s.profile()).isEqualTo(AoBackendProfile.O11Y);
        assertThat(s.profileSource()).startsWith("auto");
        assertThat(s.consoleUrl().value()).isEqualTo("https://app.au0.observability.splunkcloud.com");
        assertThat(s.apiUrl().value()).isEqualTo("https://app.au0.observability.splunkcloud.com/ao/api");
        assertThat(s.otlpEndpoint().value()).isEqualTo("https://ingest.au0.observability.splunkcloud.com/v2/trace/otlp");
        assertThat(s.otlpAuthHeader().value()).isEqualTo("X-SF-Token");
        assertThat(s.otlpAuthValue().value()).isEqualTo(O11Y_TOKEN);
        assertThat(s.apiToken().value()).isEqualTo(O11Y_TOKEN);
        assertThat(s.agentControlUrl().value()).isEqualTo("https://app.au0.observability.splunkcloud.com/ao/agent-control");
        assertThat(s.agentControlApiKeyHeader().value()).isEqualTo("X-SF-Token");
        assertThat(s.agentControlApiKey().value()).isEqualTo(O11Y_TOKEN);
        assertThat(s.agentControlEvaluationPath().value()).isEqualTo("/api/v1/evaluation");
        assertThat(s.agentControlTargetType().value()).isEqualTo("log_stream");
        assertThat(s.runtimeAuthMode().value()).isEqualTo("jwt");
        assertThat(s.runtimeTokenHeader().value()).isEqualTo("X-Agent-Control-Runtime-Token");
        assertThat(s.readFailureMode().value()).isEqualTo(ControlFailureMode.FAIL_OPEN);
        assertThat(s.writeFailureMode().value()).isEqualTo(ControlFailureMode.FAIL_CLOSED);
        assertThat(s.maxSteerRetries().value()).isEqualTo(2);
        assertThat(s.projectName().value()).isEqualTo("bank-agent-java");
        assertThat(s.agentStream().value()).isEqualTo("bank-java-demo");
        assertThat(s.serviceName().value()).isEqualTo("bank-agent-java");
        assertThat(s.apiUrl().source()).isEqualTo(Source.DERIVED);
        assertThat(s.projectRoutingAttribute()).isEqualTo("splunk_ao.project.name");
        assertThat(s.streamRoutingAttribute()).isEqualTo("splunk_ao.logstream.name");
        assertThat(s.problems()).isEmpty();
    }

    @Test
    void optionBOnlyApiKeyDerivesGalileoSaas() {
        AoSettings s = resolve("AO_API_KEY", GALILEO_KEY, "CONTROL_MODE", "remote-api");

        assertThat(s.profile()).isEqualTo(AoBackendProfile.GALILEO);
        assertThat(s.consoleUrl().value()).isEqualTo("https://app.galileo.ai");
        assertThat(s.apiUrl().value()).isEqualTo("https://api.galileo.ai");
        assertThat(s.otlpEndpoint().value()).isEqualTo("https://api.galileo.ai/otel/traces");
        assertThat(s.otlpAuthHeader().value()).isEqualTo("Galileo-API-Key");
        assertThat(s.agentControlUrl().value()).isEqualTo("https://app.galileo.ai/api/agent-control");
        assertThat(s.agentControlApiKeyHeader().value()).isEqualTo("Galileo-API-Key");
        assertThat(s.agentControlApiKey().value()).isEqualTo(GALILEO_KEY);
        assertThat(s.projectRoutingAttribute()).isEqualTo("galileo.project.name");
        assertThat(s.streamRoutingAttribute()).isEqualTo("galileo.logstream.name");
        assertThat(s.problems()).isEmpty();
    }

    @Test
    void optionCMultiTenantConsoleReplacesConsoleWithApi() {
        AoSettings s = resolve("AO_API_KEY", GALILEO_KEY,
                "AO_CONSOLE_URL", "https://console.multitenant.galileocloud.io/", "OBSERVABILITY_ENABLED", "true");

        assertThat(s.consoleUrl().value()).isEqualTo("https://console.multitenant.galileocloud.io");
        assertThat(s.consoleUrl().source()).isEqualTo(Source.OVERRIDE);
        assertThat(s.apiUrl().value()).isEqualTo("https://api.multitenant.galileocloud.io");
        assertThat(s.otlpEndpoint().value()).isEqualTo("https://api.multitenant.galileocloud.io/otel/traces");
        assertThat(s.agentControlUrl().value())
                .isEqualTo("https://console.multitenant.galileocloud.io/api/agent-control");
        assertThat(s.problems()).isEmpty();
    }

    @Test
    void consoleUrlWithTenantPathIsRejectedWithAHint() {
        AoSettings s = resolve("AO_API_KEY", GALILEO_KEY,
                "AO_CONSOLE_URL", "https://console.multitenant.galileocloud.io/splunkse", "OBSERVABILITY_ENABLED", "true");

        assertThat(s.problems()).anySatisfy(p -> assertThat(p).contains("ROOT").contains("/splunkse"));
    }

    @Test
    void bothCredentialsAreAmbiguousUntilTheProfileIsExplicit() {
        AoSettings ambiguous = resolve("AO_REALM", "au0", "AO_O11Y_TOKEN", O11Y_TOKEN, "AO_API_KEY", GALILEO_KEY,
                "OBSERVABILITY_ENABLED", "true");
        assertThat(ambiguous.problems()).anySatisfy(p -> assertThat(p).contains("AO_BACKEND_PROFILE"));

        AoSettings explicit = resolve("AO_REALM", "au0", "AO_O11Y_TOKEN", O11Y_TOKEN, "AO_API_KEY", GALILEO_KEY,
                "AO_BACKEND_PROFILE", "galileo", "OBSERVABILITY_ENABLED", "true");
        assertThat(explicit.profile()).isEqualTo(AoBackendProfile.GALILEO);
        assertThat(explicit.profileSource()).isEqualTo("explicit AO_BACKEND_PROFILE");
        assertThat(explicit.problems()).isEmpty();
        assertThat(explicit.otlpAuthValue().value()).isEqualTo(GALILEO_KEY);
    }

    @Test
    void overridesWinAndEmptyValuesOrPlaceholdersMeanUnset() {
        AoSettings s = resolve("AO_REALM", "au0", "AO_O11Y_TOKEN", O11Y_TOKEN,
                "AO_API_URL", "https://proxy.example.com/ao/api//",
                "AO_OTLP_ENDPOINT", "https://collector.example.com/v1/traces",
                "AGENT_CONTROL_URL", "", "AO_OTLP_AUTH_HEADER", "  ", "AO_CONSOLE_URL", "<console-root>",
                "AGENT_CONTROL_EVALUATION_PATH", "/api/v1/evaluation", "MAX_CONTROL_STEER_RETRIES", "1",
                "CONTROL_READ_FAILURE_MODE", "fail-closed", "AO_PROJECT_NAME", "", "AO_O11Y_API_TOKEN", "api-only");

        assertThat(s.apiUrl().value()).isEqualTo("https://proxy.example.com/ao/api");
        assertThat(s.apiUrl().source()).isEqualTo(Source.OVERRIDE);
        assertThat(s.otlpEndpoint().value()).isEqualTo("https://collector.example.com/v1/traces");
        assertThat(s.agentControlUrl().source()).isEqualTo(Source.DERIVED);
        assertThat(s.otlpAuthHeader().value()).isEqualTo("X-SF-Token");
        assertThat(s.consoleUrl().source()).isEqualTo(Source.DERIVED);
        assertThat(s.maxSteerRetries().value()).isEqualTo(1);
        assertThat(s.readFailureMode().value()).isEqualTo(ControlFailureMode.FAIL_CLOSED);
        assertThat(s.projectName().value()).isEqualTo("bank-agent-java");
        assertThat(s.apiToken().value()).isEqualTo("api-only");
        assertThat(s.otlpAuthValue().value()).isEqualTo(O11Y_TOKEN);
    }

    @Test
    void invalidRealmAndMissingCredentialsAreReported() {
        assertThat(resolve("AO_REALM", "AU-0!", "AO_O11Y_TOKEN", O11Y_TOKEN, "OBSERVABILITY_ENABLED", "true").problems())
                .anySatisfy(p -> assertThat(p).contains("AO_REALM"));
        assertThat(resolve("AO_REALM", "au0", "OBSERVABILITY_ENABLED", "true").problems())
                .anySatisfy(p -> assertThat(p).contains("AO_O11Y_TOKEN"));
        assertThat(resolve("AO_API_KEY", GALILEO_KEY, "AGENT_CONTROL_AGENT_NAME", "short", "CONTROL_MODE", "remote-api")
                .problems()).anySatisfy(p -> assertThat(p).contains("AGENT_CONTROL_AGENT_NAME"));
    }

    @Test
    void invalidSwitchValuesAlwaysFail() {
        assertThatThrownBy(() -> resolve("AO_BACKEND_PROFILE", "splunkse")).isInstanceOf(AoConfigurationException.class)
                .hasMessageContaining("tenant");
        assertThatThrownBy(() -> resolve("CONTROL_MODE", "remote")).isInstanceOf(AoConfigurationException.class);
        assertThatThrownBy(() -> resolve("OBSERVABILITY_ENABLED", "yes")).isInstanceOf(AoConfigurationException.class);
    }

    @Test
    void switchesOffMeansNoValidationButOnMeansFailFast() {
        AoConfiguration configuration = new AoConfiguration();
        MockEnvironment env = new MockEnvironment().withProperty("BANK_AGENT_GALILEO_FILE", "/tmp/galileo.properties");

        AoSettings off = configuration.aoSettings(AoRawProperties.fromKeys(Map.of("AO_API_KEY", GALILEO_KEY,
                "AO_REALM", "au0")), env);
        assertThat(off.active()).isFalse();
        assertThat(off.controlMode()).isEqualTo(ControlMode.OFF);

        assertThatThrownBy(() -> configuration.aoSettings(AoRawProperties.fromKeys(Map.of("OBSERVABILITY_ENABLED",
                "true", "AO_REALM", "au0")), env))
                .isInstanceOf(AoConfigurationException.class)
                .hasMessageContaining("/tmp/galileo.properties")
                .hasMessageContaining("o11y")
                .hasMessageContaining("docs/CONFIG-MAPPING.md")
                .hasMessageContaining("AO_O11Y_TOKEN");
    }

    @Test
    void descriptionsAndToStringNeverContainCredentials() {
        AoSettings s = resolve("AO_REALM", "au0", "AO_O11Y_TOKEN", O11Y_TOKEN, "AGENT_CONTROL_API_KEY", "ac-SECRET",
                "CONTROL_MODE", "remote-api");
        String text = String.join("\n", s.describe()) + s + AoRawProperties.fromKeys(Map.of("AO_O11Y_TOKEN", O11Y_TOKEN,
                "AO_API_KEY", GALILEO_KEY));
        assertThat(text).doesNotContain("SECRET").contains("<set>").contains("[derived]").contains("[override]");
    }

    @Test
    void otelTraceIdMapsToTheAoUuidObservedInP0() {
        assertThat(AoIds.aoTraceUuid("b8e9945f6b751f313e8d6a84fe3a2b1d"))
                .isEqualTo("b8e9945f-6b75-4f31-be8d-6a84fe3a2b1d");
        assertThat(AoIds.aoTraceUuid("2c1623414f64792896348ecb97403c48"))
                .isEqualTo("2c162341-4f64-4928-9634-8ecb97403c48");
        assertThat(AoIds.aoTraceUuid(null)).isNull();
    }
}
