package com.demo.bankagent.cli;

import com.demo.bankagent.observability.AoApiClient;
import com.demo.bankagent.observability.AoApiException;
import com.demo.bankagent.observability.AoBackendProfile;
import com.demo.bankagent.observability.AoConfigurationException;
import com.demo.bankagent.observability.AoEndpointResolver;
import com.demo.bankagent.observability.AoIds;
import com.demo.bankagent.observability.AoRawProperties;
import com.demo.bankagent.observability.AoSettings;
import com.demo.bankagent.observability.AoTargetResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

/**
 * Command-line helper used by scripts/preflight.sh and scripts/galileo-smoke.sh (plain main, no Spring context).
 * It reuses {@link AoEndpointResolver}, so the scripts show exactly the values the application would use.
 * It never prints credentials and never creates anything in the backend.
 *
 * <pre>
 *   preflight                         resolved settings + read-only reachability / project lookup
 *   smoke                             real chat questions against the running app, then trace read-back in AO
 *   trace &lt;otel-trace-id|ao-uuid&gt;...  read-only AO span tree of traces
 * </pre>
 */
public final class GalileoCli {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> KEYS = List.of("OBSERVABILITY_ENABLED", "CONTROL_MODE", "DEMO_TRACE_CONTENT",
            "AO_REALM", "AO_O11Y_TOKEN", "AO_CONSOLE_URL", "AO_API_KEY", "AO_PROJECT_NAME", "AO_AGENT_STREAM",
            "AO_BACKEND_PROFILE", "AO_API_URL", "AO_O11Y_API_TOKEN", "AO_OTLP_ENDPOINT", "AO_OTLP_AUTH_HEADER",
            "OTEL_SERVICE_NAME", "AGENT_CONTROL_URL", "AGENT_CONTROL_EVALUATION_PATH", "AGENT_CONTROL_API_KEY_HEADER",
            "AGENT_CONTROL_API_KEY", "AGENT_CONTROL_AGENT_NAME", "AGENT_CONTROL_TARGET_TYPE",
            "AGENT_CONTROL_RUNTIME_AUTH_MODE", "AGENT_CONTROL_RUNTIME_TOKEN_HEADER", "CONTROL_READ_FAILURE_MODE",
            "CONTROL_WRITE_FAILURE_MODE", "MAX_CONTROL_STEER_RETRIES");

    private GalileoCli() {
    }

    public static void main(String[] args) throws Exception {
        String command = args.length == 0 ? "preflight" : args[0];
        int code = switch (command) {
            case "preflight" -> preflight();
            case "smoke" -> smoke();
            case "trace" -> trace(List.of(args).subList(1, args.length));
            default -> {
                System.err.println("Usage: GalileoCli preflight | smoke | trace <trace-id>...");
                yield 2;
            }
        };
        System.exit(code);
    }

    // ---- configuration ------------------------------------------------------------------------------

    static Path galileoFile() {
        String configured = Optional.ofNullable(System.getenv("BANK_AGENT_GALILEO_FILE"))
                .orElse(System.getProperty("BANK_AGENT_GALILEO_FILE"));
        return configured == null || configured.isBlank()
                ? Path.of(System.getProperty("user.home"), ".config", "bank-agent-java", "galileo.properties")
                : Path.of(configured);
    }

    /** Same precedence as the application: same-named environment variable, then the private file. */
    static Map<String, String> loadKeys(Path file) throws IOException {
        Properties properties = new Properties();
        if (Files.isReadable(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                properties.load(in);
            }
        }
        Map<String, String> keys = new HashMap<>();
        for (String key : KEYS) {
            String env = System.getenv(key);
            keys.put(key, env != null ? env : properties.getProperty(key));
        }
        return keys;
    }

    private static AoSettings settings() throws IOException {
        return AoEndpointResolver.resolve(AoRawProperties.fromKeys(loadKeys(galileoFile())));
    }

    private static AoApiClient api(AoSettings s) {
        return new AoApiClient(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(), JSON,
                s.apiUrl().value(), s.profile() == AoBackendProfile.O11Y ? "X-SF-Token" : "Galileo-API-Key",
                s.apiToken().value(), Duration.ofSeconds(15), 2);
    }

    // ---- preflight ----------------------------------------------------------------------------------

    static int preflight() throws IOException {
        Path file = galileoFile();
        System.out.println("Galileo settings file: " + file + (Files.exists(file) ? "" : " (not found)"));
        AoSettings s;
        try {
            s = settings();
        } catch (AoConfigurationException e) {
            System.out.println("[FAIL] " + e.getMessage());
            return 1;
        }
        s.describe().forEach(line -> System.out.println("  " + line));
        if (!s.active()) {
            System.out.println("[ OK ] Observe and Agent Control are off: nothing is validated or contacted at startup.");
            return 0;
        }
        if (!s.problems().isEmpty()) {
            System.out.println("[FAIL] The application would refuse to start with these settings (see problems above,"
                    + " docs/CONFIG-MAPPING.md).");
            return 1;
        }
        int failures = 0;
        AoApiClient api = api(s);
        try {
            api.get("/healthcheck");
            System.out.println("[ OK ] AO API reachable and credential accepted: " + AoSettings.host(s.apiUrl().value()));
            AoTargetResolver resolver = new AoTargetResolver(api, s.profile());
            Optional<String> project = resolver.findProject(s.projectName().value());
            if (project.isEmpty()) {
                System.out.println("[INFO] Project '" + s.projectName().value()
                        + "' does not exist yet; the application will create it at startup.");
            } else {
                System.out.println("[ OK ] Project '" + s.projectName().value() + "' exists (" + project.get() + ")");
                Optional<String> stream = resolver.findStream(project.get(), s.agentStream().value());
                System.out.println(stream.map(id -> "[ OK ] Agent stream '" + s.agentStream().value() + "' exists ("
                                + id + ")")
                        .orElse("[INFO] Agent stream '" + s.agentStream().value()
                                + "' does not exist yet; the application will create it at startup."));
            }
        } catch (AoApiException e) {
            System.out.println("[FAIL] AO API: " + e.getMessage());
            failures++;
        }
        if (s.observabilityEnabled()) {
            int status = rawStatus(s.otlpEndpoint().value(), Map.of());
            System.out.println((status > 0 ? "[ OK ] " : "[FAIL] ") + "OTLP endpoint "
                    + AoSettings.host(s.otlpEndpoint().value()) + " reachable (HTTP " + status
                    + " to an unauthenticated GET; spans are POSTed as protobuf)");
            failures += status > 0 ? 0 : 1;
        }
        if (s.controlEnabled()) {
            int status = rawStatus(s.agentControlUrl().value() + "/health",
                    Map.of(s.agentControlApiKeyHeader().value(), s.agentControlApiKey().value()));
            System.out.println((status == 200 ? "[ OK ] " : "[FAIL] ") + "Agent Control health at "
                    + AoSettings.host(s.agentControlUrl().value()) + " (HTTP " + status + ")");
            failures += status == 200 ? 0 : 1;
        }
        return failures == 0 ? 0 : 1;
    }

    private static int rawStatus(String url, Map<String, String> headers) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET();
            headers.forEach(b::header);
            return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
                    .send(b.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (IOException e) {
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    // ---- smoke ----------------------------------------------------------------------------------------

    static int smoke() throws Exception {
        String base = "http://" + env("BANK_AGENT_HOST", "127.0.0.1") + ":" + env("BANK_AGENT_PORT", "8080");
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        JsonNode status = JSON.readTree(http.send(HttpRequest.newBuilder(URI.create(base + "/api/status")).build(),
                HttpResponse.BodyHandlers.ofString()).body());
        System.out.println("Application: " + base + " model=" + status.path("model").asText()
                + " observability=" + status.path("observability").path("enabled").asBoolean()
                + " control=" + status.path("control").path("mode").asText());
        System.out.println("Control gateway: " + status.path("control").path("gateway").asText());
        System.out.println("Control event channel: " + status.path("control").path("eventChannel").asText());
        List<String> questions = List.of("我现在的活期账户余额是多少？", "把我的账户余额和最近五笔交易一起告诉我。",
                "上个月我在哪类消费上花钱最多？一共花了多少？");
        List<String> traces = new ArrayList<>();
        int failures = 0;
        for (String question : questions) {
            ObjectNode body = JSON.createObjectNode().put("message", question);
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(base + "/api/chat"))
                    .timeout(Duration.ofSeconds(180)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode turn = JSON.readTree(response.body());
            if (response.statusCode() != 200) {
                System.out.println("[FAIL] " + question + " -> HTTP " + response.statusCode() + " "
                        + turn.path("code").asText());
                failures++;
                continue;
            }
            List<String> controls = new ArrayList<>();
            turn.path("controlDecisions").forEach(c -> controls.add(c.path("stage").asText() + ":"
                    + c.path("stepName").asText() + "=" + c.path("verdict").asText()));
            System.out.println("[ OK ] " + question + "\n       agents=" + turn.path("selectedAgents") + " tools="
                    + turn.path("executedTools") + " llmCalls=" + turn.path("llmCalls").size() + " durationMs="
                    + turn.path("durationMs").asLong() + "\n       traceId=" + turn.path("traceId").asText("-")
                    + " aoTraceId=" + turn.path("aoTraceId").asText("-") + " controls=" + controls);
            if (turn.hasNonNull("traceId")) {
                traces.add(turn.path("traceId").asText());
            }
        }
        if (!traces.isEmpty()) {
            System.out.println("Waiting for AO ingestion, then reading the traces back (read-only)...");
            failures += trace(traces);
        }
        return failures == 0 ? 0 : 1;
    }

    // ---- trace read-back ------------------------------------------------------------------------------

    static int trace(List<String> ids) throws Exception {
        AoSettings s = settings();
        if (!s.apiToken().isSet() || s.apiUrl().value() == null) {
            System.out.println("[FAIL] AO credentials are not configured; cannot read traces back.");
            return 1;
        }
        AoApiClient api = api(s);
        AoTargetResolver resolver = new AoTargetResolver(api, s.profile());
        String projectId = resolver.findProject(s.projectName().value()).orElse(null);
        if (projectId == null) {
            System.out.println("[FAIL] Project '" + s.projectName().value() + "' not found.");
            return 1;
        }
        String tracePath = s.profile() == AoBackendProfile.O11Y ? "/projects/" : "/v2/projects/";
        int failures = 0;
        for (String id : ids) {
            String uuid = id.contains("-") ? id : AoIds.aoTraceUuid(id);
            JsonNode trace = null;
            for (int attempt = 0; attempt < 24 && trace == null; attempt++) {
                try {
                    trace = api.get(tracePath + projectId + "/traces/" + uuid);
                } catch (AoApiException e) {
                    Thread.sleep(10_000);
                }
            }
            if (trace == null) {
                System.out.println("[FAIL] trace " + uuid + " not visible in AO after 4 minutes");
                failures++;
                continue;
            }
            System.out.println("[ OK ] AO trace " + uuid + " (session " + trace.path("session_id").asText() + ")");
            print(trace, 1);
        }
        return failures;
    }

    private static void print(JsonNode node, int depth) {
        for (JsonNode child : node.path("spans")) {
            JsonNode metrics = child.path("metrics");
            String extra = "";
            if ("llm".equals(child.path("type").asText())) {
                extra = " tokens=" + metrics.path("num_input_tokens").asText("-") + "/"
                        + metrics.path("num_output_tokens").asText("-");
            } else if ("control".equals(child.path("type").asText())) {
                extra = " control_id=" + child.path("control_id").asText() + " stage="
                        + child.path("check_stage").asText() + " action=" + child.path("output").path("action").asText()
                        + " matched=" + child.path("output").path("matched").asText();
            }
            System.out.println("  ".repeat(depth) + "- " + child.path("type").asText() + " | "
                    + child.path("name").asText() + " | status=" + child.path("status_code").asText() + extra);
            print(child, depth + 1);
        }
    }

    private static String env(String key, String def) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? def : value;
    }
}
