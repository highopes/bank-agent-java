package com.demo.bankagent.control;

import com.demo.bankagent.observability.AoEndpointResolver;
import com.demo.bankagent.observability.AoRawProperties;
import com.demo.bankagent.observability.AoSettings;
import com.demo.bankagent.observability.AoTarget;
import com.demo.bankagent.support.MockHttpServer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract test of the Java Agent Control adapter against a loopback mock of the Agent Control server, using
 * JSON fixtures recorded from the real Splunk AO SaaS in P0 (task book 9.1 ControlGatewayTest).
 */
class ControlGatewayTest {

    private static final String TOKEN = "o11y-token-SECRET";
    private static final String RUNTIME = "runtime-jwt-SECRET";
    private static final String LSID = "3bc831a3-958e-4d42-ac9c-7ca1dfd624cb";

    private final ObjectMapper mapper = new ObjectMapper();
    private MockHttpServer server;

    @BeforeEach
    void start() throws IOException {
        server = new MockHttpServer();
        server.always("GET", "/health", 200, "{\"status\":\"healthy\",\"version\":\"8.15.0\"}");
        server.always("POST", "/api/v1/agents/initAgent", 200, "{\"created\":true,\"controls\":[]}");
        server.always("POST", "/api/v1/auth/runtime-token-exchange", 200, tokenResponse(RUNTIME));
        server.always("GET", "/api/v1/agents/bank-agent-java/controls", 200, fixture("agent-controls.json"));
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private String tokenResponse(String token) {
        return "{\"token\":\"" + token + "\",\"expires_at\":\"" + Instant.now().plusSeconds(300)
                + "\",\"target_type\":\"log_stream\",\"target_id\":\"" + LSID + "\",\"scopes\":[\"runtime.use\"]}";
    }

    private RemoteAgentControlGateway gateway(boolean prefilter) {
        AoSettings settings = AoEndpointResolver.resolve(AoRawProperties.fromKeys(Map.of(
                "AO_REALM", "au0", "AO_O11Y_TOKEN", TOKEN, "CONTROL_MODE", "remote-api",
                "AGENT_CONTROL_URL", server.baseUrl())));
        AoTarget target = new AoTarget("bank-agent-java", "pid", false, "bank-java-demo", LSID, false);
        ControlProperties props = new ControlProperties(Duration.ofMillis(500), Duration.ofMillis(700), 2,
                Duration.ofSeconds(30), Duration.ofSeconds(5), prefilter, 1);
        RemoteAgentControlGateway gateway = new RemoteAgentControlGateway(settings, target, props, mapper,
                new StepCatalog(), "test");
        gateway.start();
        return gateway;
    }

    private static ControlStep tool(String name, String json) {
        try {
            return ControlStep.tool(name, new ObjectMapper().readTree(json), null, "conv-1", StepAccess.READ);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String fixture(String name) {
        try (InputStream in = ControlGatewayTest.class.getResourceAsStream("/agent-control/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void startRegistersTheAgentStepCatalogAndTarget() throws Exception {
        gateway(true);

        JsonNode init = mapper.readTree(server.requests("POST", "/api/v1/agents/initAgent").get(0).body());
        assertThat(init.path("agent").path("agent_name").asText()).isEqualTo("bank-agent-java");
        assertThat(init.path("conflict_mode").asText()).isEqualTo("overwrite");
        assertThat(init.path("target_type").asText()).isEqualTo("log_stream");
        assertThat(init.path("target_id").asText()).isEqualTo(LSID);
        assertThat(init.path("steps").findValuesAsText("name")).contains("banking-assistant", "supervisor-router",
                "account-agent", "payments-agent", "createTransferDraft", "getAccountBalance", "payment.confirm");
        JsonNode exchange = mapper.readTree(server.requests("POST", "/api/v1/auth/runtime-token-exchange").get(0).body());
        assertThat(exchange.path("target_id").asText()).isEqualTo(LSID);
        assertThat(server.requests()).allSatisfy(r -> assertThat(r.header("X-SF-Token")).isEqualTo(TOKEN));
        assertThat(server.requests("GET", "/api/v1/agents/bank-agent-java/controls").get(0).query())
                .contains("target_type=log_stream").contains("target_id=" + LSID);
    }

    @Test
    void evaluationCarriesStepTargetAndRuntimeToken() throws Exception {
        server.always("POST", "/api/v1/evaluation", 200, fixture("evaluation-observe.json"));

        ControlDecision decision = gateway(true).evaluate(ControlStage.PRE,
                tool("getAccountBalance", "{\"accountType\":\"CHECKING\"}"));

        MockHttpServer.Request request = server.requests("POST", "/api/v1/evaluation").get(0);
        assertThat(request.header("X-Agent-Control-Runtime-Token")).isEqualTo(RUNTIME);
        assertThat(request.header("X-SF-Token")).isEqualTo(TOKEN);
        JsonNode body = mapper.readTree(request.body());
        assertThat(body.path("agent_name").asText()).isEqualTo("bank-agent-java");
        assertThat(body.path("stage").asText()).isEqualTo("pre");
        assertThat(body.path("target_type").asText()).isEqualTo("log_stream");
        assertThat(body.path("target_id").asText()).isEqualTo(LSID);
        assertThat(body.path("step").path("type").asText()).isEqualTo("tool");
        assertThat(body.path("step").path("name").asText()).isEqualTo("getAccountBalance");
        assertThat(body.path("step").path("input").path("accountType").asText()).isEqualTo("CHECKING");
        assertThat(body.path("step").has("output")).isFalse();

        assertThat(decision.status()).isEqualTo(ControlDecision.Status.EVALUATED);
        assertThat(decision.action()).isEqualTo(ControlAction.OBSERVE);
        assertThat(decision.controlId()).isEqualTo(13L);
        assertThat(decision.results()).singleElement().satisfies(r -> {
            assertThat(r.category()).isEqualTo(ControlMatchResult.Category.MATCH);
            assertThat(r.evaluatorName()).isEqualTo("regex");
            assertThat(r.selectorPath()).isEqualTo("input");
            assertThat(r.selectedPreview()).isEqualTo("{\"accountType\":\"CHECKING\"}");
        });
    }

    @Test
    void responsesMapWithDenySteerObservePriority() throws Exception {
        assertThat(map("evaluation-deny.json").action()).isEqualTo(ControlAction.DENY);
        assertThat(map("evaluation-deny.json").controlName()).isEqualTo("bank-java-p0-deny-probe");
        ControlDecision steer = map("evaluation-steer.json");
        assertThat(steer.action()).isEqualTo(ControlAction.STEER);
        assertThat(steer.steeringContext()).isEqualTo("Answer in one short sentence and do not mention balances.");
        assertThat(map("evaluation-allow.json").action()).isEqualTo(ControlAction.ALLOW);
        ControlDecision nonMatch = map("evaluation-non-match.json");
        assertThat(nonMatch.action()).isEqualTo(ControlAction.ALLOW);
        assertThat(nonMatch.results()).singleElement()
                .satisfies(r -> assertThat(r.category()).isEqualTo(ControlMatchResult.Category.NON_MATCH));
        ControlDecision both = map("evaluation-deny-and-steer.json");
        assertThat(both.action()).isEqualTo(ControlAction.DENY);
        assertThat(both.controlName()).isEqualTo("deny-second-in-list");
        assertThat(map("evaluation-legacy-warn.json").action()).isEqualTo(ControlAction.OBSERVE);
    }

    @Test
    void unknownActionsAndUnsafeWithoutMatchFailSafe() throws Exception {
        ControlDecision unknown = map("evaluation-unknown-action.json");
        assertThat(unknown.action()).isEqualTo(ControlAction.DENY);
        assertThat(unknown.reason()).contains("quarantine");
        assertThat(map("evaluation-unsafe-without-match.json").action()).isEqualTo(ControlAction.DENY);
    }

    @Test
    void evaluatorErrorsAreUnavailableNotAllowOrDeny() throws Exception {
        ControlDecision errors = map("evaluation-errors.json");
        assertThat(errors.status()).isEqualTo(ControlDecision.Status.UNAVAILABLE);
        assertThat(errors.failureCode()).isEqualTo("EVALUATOR_ERROR");
        assertThat(errors.reason()).contains("luna-pii-check").contains("evaluator timeout");
        assertThat(errors.results()).singleElement()
                .satisfies(r -> assertThat(r.category()).isEqualTo(ControlMatchResult.Category.ERROR));
    }

    @Test
    void malformedResponsesAreContractErrors() {
        assertThatThrownBy(() -> RemoteAgentControlGateway.mapResponse(mapper.readTree("{\"decision\":\"deny\"}"), 1))
                .isInstanceOfSatisfying(ControlTransportException.class, e -> assertThat(e.code()).isEqualTo("CONTRACT"));
        assertThatThrownBy(() -> RemoteAgentControlGateway.mapResponse(mapper.readTree(
                "{\"is_safe\":false,\"matches\":[{\"control_name\":\"x\"}]}"), 1))
                .isInstanceOf(ControlTransportException.class);
    }

    @Test
    void prefilterSkipsStepsWithoutApplicableControls() {
        RemoteAgentControlGateway gateway = gateway(true);

        ControlDecision skipped = gateway.evaluate(ControlStage.PRE, tool("listBeneficiaries", "{}"));
        ControlDecision skippedStage = gateway.evaluate(ControlStage.POST, tool("getAccountBalance", "{}"));

        assertThat(skipped.status()).isEqualTo(ControlDecision.Status.SKIPPED);
        assertThat(skippedStage.status()).isEqualTo(ControlDecision.Status.SKIPPED);
        assertThat(server.requests("POST", "/api/v1/evaluation")).isEmpty();
    }

    @Test
    void prefilterHonoursStepNameRegexAndReportsSdkOnlyControls() {
        server.always("POST", "/api/v1/evaluation", 200, fixture("evaluation-allow.json"));
        RemoteAgentControlGateway gateway = gateway(true);

        gateway.evaluate(ControlStage.POST, ControlStep.llm("payments-agent", "q", "a", "conv-1"));
        assertThat(server.requests("POST", "/api/v1/evaluation")).hasSize(1);

        ControlDecision sdk = gateway.evaluate(ControlStage.PRE, tool("searchTransactions", "{}"));
        assertThat(sdk.status()).isEqualTo(ControlDecision.Status.UNAVAILABLE);
        assertThat(sdk.failureCode()).isEqualTo("UNSUPPORTED_EXECUTION");
        assertThat(sdk.reason()).contains("sdk-only-control");
    }

    @Test
    void serverErrorsAreRetriedOnceThenReportedAsUnavailable() {
        server.once("POST", "/api/v1/evaluation", 503, "{\"detail\":\"busy\"}");
        server.once("POST", "/api/v1/evaluation", 200, fixture("evaluation-allow.json"));
        RemoteAgentControlGateway gateway = gateway(false);

        assertThat(gateway.evaluate(ControlStage.PRE, tool("listBeneficiaries", "{}")).action())
                .isEqualTo(ControlAction.ALLOW);

        server.always("POST", "/api/v1/evaluation", 500, "{\"detail\":\"boom\"}");
        assertThatThrownBy(() -> gateway.evaluate(ControlStage.PRE, tool("listBeneficiaries", "{}")))
                .isInstanceOfSatisfying(ControlTransportException.class, e -> {
                    assertThat(e.code()).isEqualTo("SERVER_ERROR");
                    assertThat(e.httpStatus()).isEqualTo(500);
                    assertThat(e.getMessage()).doesNotContain(TOKEN).doesNotContain(RUNTIME);
                });
    }

    @Test
    void timeoutsAreReportedAsUnavailable() {
        server.always("POST", "/api/v1/evaluation",
                r -> new MockHttpServer.Response(200, fixture("evaluation-allow.json"), Map.of(), 2000));
        RemoteAgentControlGateway gateway = gateway(false);

        assertThatThrownBy(() -> gateway.evaluate(ControlStage.PRE, tool("listBeneficiaries", "{}")))
                .isInstanceOfSatisfying(ControlTransportException.class,
                        e -> assertThat(e.code()).isEqualTo("TIMEOUT"));
        assertThat(server.requests("POST", "/api/v1/evaluation")).hasSize(2);
    }

    @Test
    void rejectedRuntimeTokenIsRefreshedOnce() {
        server.once("POST", "/api/v1/evaluation", r -> new MockHttpServer.Response(401,
                "{\"error_code\":\"AUTH_INVALID_TOKEN\",\"detail\":\"token expired\"}",
                Map.of("WWW-Authenticate", "Bearer error=\"invalid_token\""), 0));
        server.once("POST", "/api/v1/evaluation", 200, fixture("evaluation-allow.json"));
        RemoteAgentControlGateway gateway = gateway(false);

        assertThat(gateway.evaluate(ControlStage.PRE, tool("listBeneficiaries", "{}")).action())
                .isEqualTo(ControlAction.ALLOW);
        assertThat(server.requests("POST", "/api/v1/auth/runtime-token-exchange")).hasSize(2);
    }

    @Test
    void authErrorsAreNotRetriedAndNeverLeakCredentials() {
        server.always("POST", "/api/v1/evaluation", 403, "{\"error_code\":\"FORBIDDEN\",\"detail\":\"no access\"}");
        RemoteAgentControlGateway gateway = gateway(false);

        assertThatThrownBy(() -> gateway.evaluate(ControlStage.PRE, tool("listBeneficiaries", "{}")))
                .isInstanceOfSatisfying(ControlTransportException.class, e -> {
                    assertThat(e.code()).isEqualTo("AUTH");
                    assertThat(e.getMessage()).contains("403").doesNotContain(TOKEN).doesNotContain(RUNTIME);
                });
        assertThat(server.requests("POST", "/api/v1/evaluation")).hasSize(1);
    }

    private ControlDecision map(String fixture) throws Exception {
        return RemoteAgentControlGateway.mapResponse(mapper.readTree(fixture(fixture)), 1);
    }
}
