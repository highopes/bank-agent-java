package com.demo.bankagent.observability;

import com.demo.bankagent.support.MockHttpServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * "Find by name, create when missing" for both profiles against a loopback mock (no network). Response shapes are
 * the ones recorded from the real au0 SaaS during protocol verification (o11y) and the public Galileo OpenAPI (galileo).
 */
class AoTargetResolverTest {

    private static final String TOKEN = "token-SECRET-xyz";
    private static final String PID = "7f8db462-22fd-417b-a8a4-68d61ff16b5a";
    private static final String LSID = "3bc831a3-958e-4d42-ac9c-7ca1dfd624cb";

    private final ObjectMapper mapper = new ObjectMapper();
    private MockHttpServer server;

    @BeforeEach
    void start() throws Exception {
        server = new MockHttpServer();
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private AoTargetResolver o11y() {
        return resolver(AoBackendProfile.O11Y, server.baseUrl() + "/ao/api", "X-SF-Token", Duration.ofSeconds(5));
    }

    private AoTargetResolver resolver(AoBackendProfile profile, String base, String header, Duration timeout) {
        AoApiClient api = new AoApiClient(HttpClient.newHttpClient(), mapper, base, header, TOKEN, timeout, 2);
        return new AoTargetResolver(api, profile);
    }

    @Test
    void o11yExistingProjectAndStreamAreReusedWithoutCreating() {
        server.always("GET", "/ao/api/projects", 200,
                "[{\"id\":\"" + PID + "\",\"name\":\"bank-agent-java\",\"type\":\"gen_ai\"}]");
        server.always("GET", "/ao/api/projects/" + PID + "/log_streams", 200,
                "[{\"id\":\"other\",\"name\":\"other\"},{\"id\":\"" + LSID + "\",\"name\":\"bank-java-demo\"}]");

        AoTarget target = o11y().resolve("bank-agent-java", "bank-java-demo");

        assertThat(target.projectId()).isEqualTo(PID);
        assertThat(target.agentStreamId()).isEqualTo(LSID);
        assertThat(target.projectCreated()).isFalse();
        assertThat(target.agentStreamCreated()).isFalse();
        assertThat(server.requests()).noneMatch(r -> r.method().equals("POST"));
        assertThat(server.requests()).allSatisfy(r -> assertThat(r.header("X-SF-Token")).isEqualTo(TOKEN));
        assertThat(server.requests("GET", "/ao/api/projects").get(0).query())
                .isEqualTo("project_name=bank-agent-java&type=gen_ai");
    }

    @Test
    void o11yMissingProjectAndStreamAreCreatedInOrder() throws Exception {
        server.always("GET", "/ao/api/projects", 200, "[]");
        server.always("POST", "/ao/api/projects", 200,
                "{\"name\":\"bank-agent-java\",\"type\":\"gen_ai\",\"id\":\"" + PID + "\"}");
        server.always("GET", "/ao/api/projects/" + PID + "/log_streams", 200, "[]");
        server.always("POST", "/ao/api/projects/" + PID + "/log_streams", 200,
                "{\"id\":\"" + LSID + "\",\"name\":\"bank-java-demo\",\"project_id\":\"" + PID + "\"}");

        AoTarget target = o11y().resolve("bank-agent-java", "bank-java-demo");

        assertThat(target.projectCreated()).isTrue();
        assertThat(target.agentStreamCreated()).isTrue();
        assertThat(target.agentStreamId()).isEqualTo(LSID);
        assertThat(mapper.readTree(server.requests("POST", "/ao/api/projects").get(0).body()))
                .isEqualTo(mapper.readTree(
                        "{\"name\":\"bank-agent-java\",\"type\":\"gen_ai\",\"create_example_templates\":false}"));
        assertThat(mapper.readTree(server.requests("POST", "/ao/api/projects/" + PID + "/log_streams").get(0).body()))
                .isEqualTo(mapper.readTree("{\"name\":\"bank-java-demo\"}"));
    }

    @Test
    void createConflictIsResolvedByOneReQuery() {
        server.once("GET", "/ao/api/projects", 200, "[]");
        server.always("GET", "/ao/api/projects", 200,
                "[{\"id\":\"" + PID + "\",\"name\":\"bank-agent-java\",\"type\":\"gen_ai\"}]");
        server.always("POST", "/ao/api/projects", 422,
                "{\"detail\":\"A project with this (name, type) already exists.\"}");
        server.once("GET", "/ao/api/projects/" + PID + "/log_streams", 200, "[]");
        server.always("GET", "/ao/api/projects/" + PID + "/log_streams", 200,
                "[{\"id\":\"" + LSID + "\",\"name\":\"bank-java-demo\"}]");
        server.always("POST", "/ao/api/projects/" + PID + "/log_streams", 400,
                "{\"detail\":\"Log stream bank-java-demo already exists for project bank-agent-java.\"}");

        AoTarget target = o11y().resolve("bank-agent-java", "bank-java-demo");

        assertThat(target.projectId()).isEqualTo(PID);
        assertThat(target.agentStreamId()).isEqualTo(LSID);
        assertThat(target.projectCreated()).isFalse();
        assertThat(server.requests("POST", "/ao/api/projects")).hasSize(1);
    }

    @Test
    void sameNameButNotAnAgentObservabilityProjectIsAnError() {
        server.always("GET", "/ao/api/projects", 200,
                "[{\"id\":\"" + PID + "\",\"name\":\"bank-agent-java\",\"type\":\"prompt_evaluation\"}]");

        assertThatThrownBy(() -> o11y().resolve("bank-agent-java", "bank-java-demo"))
                .isInstanceOf(AoApiException.class).hasMessageContaining("not a gen_ai");
    }

    @Test
    void forbiddenIsAClearAuthErrorWithoutTheCredential() {
        server.always("GET", "/ao/api/projects", 403, "{\"detail\":\"Not enough permissions\"}");

        assertThatThrownBy(() -> o11y().resolve("bank-agent-java", "bank-java-demo"))
                .isInstanceOfSatisfying(AoApiException.class, e -> {
                    assertThat(e.isAuthError()).isTrue();
                    assertThat(e.getMessage()).contains("403").contains("Not enough permissions")
                            .doesNotContain(TOKEN);
                });
        assertThat(server.requests()).hasSize(1);
    }

    @Test
    void timeoutsAreRetriedABoundedNumberOfTimes() {
        server.always("GET", "/ao/api/projects",
                r -> new MockHttpServer.Response(200, "[]", java.util.Map.of(), 1500));
        AoTargetResolver resolver = resolver(AoBackendProfile.O11Y, server.baseUrl() + "/ao/api", "X-SF-Token",
                Duration.ofMillis(300));

        assertThatThrownBy(() -> resolver.resolve("bank-agent-java", "bank-java-demo"))
                .isInstanceOf(AoApiException.class).hasMessageContaining("timed out");
        assertThat(server.requests("GET", "/ao/api/projects")).hasSize(2);
    }

    @Test
    void galileoUsesThePublicV2RoutesAndGalileoApiKey() throws Exception {
        server.always("POST", "/v2/projects/paginated", 200, "{\"projects\":[],\"total_count\":0}");
        server.always("POST", "/v2/projects", 200, "{\"id\":\"" + PID + "\",\"name\":\"bank-agent-java\"}");
        server.always("GET", "/v2/projects/" + PID + "/log_streams", 200, "[]");
        server.always("POST", "/v2/projects/" + PID + "/log_streams", 200,
                "{\"id\":\"" + LSID + "\",\"name\":\"bank-java-demo\",\"project_id\":\"" + PID + "\"}");

        AoTarget target = resolver(AoBackendProfile.GALILEO, server.baseUrl(), "Galileo-API-Key", Duration.ofSeconds(5))
                .resolve("bank-agent-java", "bank-java-demo");

        assertThat(target.agentStreamId()).isEqualTo(LSID);
        assertThat(server.requests()).allSatisfy(r -> {
            assertThat(r.header("Galileo-API-Key")).isEqualTo(TOKEN);
            assertThat(r.header("X-SF-Token")).isNull();
        });
        assertThat(mapper.readTree(server.requests("POST", "/v2/projects/paginated").get(0).body()))
                .isEqualTo(mapper.readTree("{\"filters\":[{\"name\":\"name\",\"operator\":\"eq\","
                        + "\"value\":\"bank-agent-java\",\"case_sensitive\":true}]}"));
        assertThat(mapper.readTree(server.requests("POST", "/v2/projects").get(0).body()))
                .isEqualTo(mapper.readTree("{\"name\":\"bank-agent-java\",\"type\":\"gen_ai\"}"));
    }

    @Test
    void galileoExistingProjectWithoutTypeFieldIsAccepted() {
        server.always("POST", "/v2/projects/paginated", 200,
                "{\"projects\":[{\"id\":\"" + PID + "\",\"name\":\"bank-agent-java\"}],\"total_count\":1}");
        server.always("GET", "/v2/projects/" + PID + "/log_streams", 200,
                "[{\"id\":\"" + LSID + "\",\"name\":\"bank-java-demo\"}]");

        AoTarget target = resolver(AoBackendProfile.GALILEO, server.baseUrl(), "Galileo-API-Key", Duration.ofSeconds(5))
                .resolve("bank-agent-java", "bank-java-demo");

        assertThat(target.projectId()).isEqualTo(PID);
        assertThat(server.requests()).noneMatch(r -> r.path().equals("/v2/projects") && r.method().equals("POST"));
    }
}
