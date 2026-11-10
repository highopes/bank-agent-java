package com.demo.bankagent.observability;

import com.demo.bankagent.support.MockHttpServer;
import com.demo.bankagent.support.OtlpReceiver;
import com.demo.bankagent.support.OtlpReceiver.ReceivedSpan;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Black-box acceptance of the four Observe x Control combinations with the REAL OpenTelemetry Java agent. The
 * packaged jar runs in its own JVM, started like scripts/run.sh starts it: {@code -javaagent} +
 * {@code config/otel-javaagent.properties} + the OTEL_* variables derived from the galileo keys
 * ({@link AoSettings#otelAgentEnvironment()}, the code behind {@code GalileoCli otel-env}). OpenAI, the AO REST API,
 * Agent Control and the AO OTLP endpoint are loopback mocks; nothing leaves the machine.
 */
class OtelJavaAgentIT {

    private static final String AO_TOKEN = "ao-token-SECRET-4711";
    private static final String OPENAI_KEY = "sk-test-SECRET-0815";
    private static final String RESPONSE_MODEL = "gpt-5-nano-2025-08-07";
    private static final String LSID = "lsid-it";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private MockHttpServer mocks;
    private OtlpReceiver otlp;
    private Process app;
    private Path dataDir;
    private Path log;
    private String base;

    @BeforeEach
    void startMocks() throws IOException {
        assumeTrue(System.getProperty("bank.it.otelAgentJar") != null, "run with Maven (failsafe) to get the agent");
        otlp = new OtlpReceiver();
        mocks = new MockHttpServer();
        mocks.always("POST", "/v1/chat/completions", OtelJavaAgentIT::openAi);
        mocks.always("GET", "/ao/api/projects", 200, "[{\"id\":\"pid-it\",\"name\":\"bank-agent-java\",\"type\":\"gen_ai\"}]");
        mocks.always("GET", "/ao/api/projects/pid-it/log_streams", 200,
                "[{\"id\":\"" + LSID + "\",\"name\":\"bank-java-demo\"}]");
        String ac = "/ao/agent-control";
        mocks.always("GET", ac + "/health", 200, "{\"status\":\"healthy\",\"version\":\"8.15.0\"}");
        mocks.always("POST", ac + "/api/v1/agents/initAgent", 200, "{\"created\":true,\"controls\":[]}");
        mocks.always("POST", ac + "/api/v1/auth/runtime-token-exchange", 200, "{\"token\":\"runtime-jwt\",\"expires_at\":\""
                + Instant.now().plusSeconds(300) + "\",\"target_type\":\"log_stream\",\"target_id\":\"" + LSID
                + "\",\"scopes\":[\"runtime.use\"]}");
        mocks.always("GET", ac + "/api/v1/agents/bank-agent-java/controls", 200, fixture("agent-controls.json"));
        mocks.always("POST", ac + "/api/v1/evaluation", r -> {
            boolean balancePre = r.body().contains("\"getAccountBalance\"") && r.body().contains("\"stage\":\"pre\"");
            return MockHttpServer.Response.json(200, fixture(balancePre ? "evaluation-observe.json"
                    : "evaluation-allow.json"));
        });
    }

    @AfterEach
    void stopAll() throws Exception {
        if (app != null) {
            app.destroy();
            if (!app.waitFor(Duration.ofSeconds(20).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
                app.destroyForcibly();
            }
        }
        if (mocks != null) {
            mocks.close();
        }
        if (otlp != null) {
            otlp.close();
        }
        if (dataDir != null) {
            try (var files = Files.walk(dataDir)) {
                files.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    @Test
    void pinnedAgentVersionAndChecksumMatchTheStartScript() throws Exception {
        String script = Files.readString(Path.of(System.getProperty("bank.it.projectDir"), "scripts/lib/common.sh"));
        assertThat(match(script, "OTEL_JAVAAGENT_VERSION=\"([^\"]+)\""))
                .isEqualTo(System.getProperty("bank.it.otelAgentVersion"));
        byte[] jar = Files.readAllBytes(Path.of(System.getProperty("bank.it.otelAgentJar")));
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(jar)))
                .isEqualTo(match(script, "OTEL_JAVAAGENT_SHA256=\"([0-9a-f]{64})\""));
    }

    @Test
    void observeOnControlOff_agentExportsTheGenAiTreeToTheAoEndpoint() throws Exception {
        start(true, false, Map.of());

        JsonNode balance = chat(null, "我现在的活期账户余额是多少？");
        JsonNode transfer = chat(balance.path("conversationId").asText(), "向张三转账 500 元。");
        String conversation = balance.path("conversationId").asText();
        String draftId = transfer.path("pendingDraft").path("draftId").asText();
        HttpResponse<String> confirm = post("/api/payments/drafts/" + draftId + "/confirm",
                "{\"conversationId\":\"" + conversation + "\"}");
        assertThat(confirm.statusCode()).isEqualTo(200);
        String confirmTrace = confirm.headers().firstValue("X-Bank-Trace-Id").orElseThrow();

        List<ReceivedSpan> chatTrace = awaitTrace(balance.path("traceId").asText());
        List<ReceivedSpan> confirmSpans = awaitTrace(confirmTrace);

        // Export: one standard OTLP/HTTP protobuf exporter (the agent's) with AO's path, token and routing headers.
        assertThat(otlp.exports()).isNotEmpty().allSatisfy(e -> {
            assertThat(e.method()).isEqualTo("POST");
            assertThat(e.path()).isEqualTo("/v2/trace/otlp");
            assertThat(e.headers().get("content-type")).isEqualTo("application/x-protobuf");
            assertThat(e.headers().get("x-sf-token")).isEqualTo(AO_TOKEN);
            assertThat(e.headers().get("project")).isEqualTo("bank-agent-java");
            assertThat(e.headers().get("logstream")).isEqualTo("bank-java-demo");
        });
        assertThat(chatTrace.get(0).resource()).containsEntry("service.name", "bank-agent-java")
                .containsEntry("splunk_ao.project.name", "bank-agent-java")
                .containsEntry("splunk_ao.logstream.name", "bank-java-demo");

        // Tree: HTTP span (agent) -> chat turn (hand-written) -> router LLM + AccountAgent -> LLM / tool.
        ReceivedSpan http = single(chatTrace, s -> s.isRoot());
        assertThat(http.name()).isEqualTo("POST /api/chat");
        assertThat(http.kind()).isEqualTo("SPAN_KIND_SERVER");
        ReceivedSpan root = single(chatTrace, s -> s.name().equals("invoke_agent BankingAssistant"));
        assertThat(root.parentSpanId()).isEqualTo(http.spanId());
        ReceivedSpan agent = single(chatTrace, s -> s.name().equals("invoke_agent AccountAgent"));
        assertThat(agent.parentSpanId()).isEqualTo(root.spanId());
        ReceivedSpan tool = single(chatTrace, s -> s.name().equals("execute_tool getAccountBalance"));
        assertThat(tool.parentSpanId()).isEqualTo(agent.spanId());
        List<ReceivedSpan> llm = chatTrace.stream().filter(s -> "chat".equals(s.attr(GenAiAttributes.OPERATION_NAME)))
                .toList();
        assertThat(llm).hasSize(3);
        assertThat(llm).filteredOn(s -> s.parentSpanId().equals(root.spanId())).hasSize(1);
        assertThat(llm).filteredOn(s -> s.parentSpanId().equals(agent.spanId())).hasSize(2);
        assertThat(llm).allSatisfy(s -> {
            assertThat(s.name()).isEqualTo("chat gpt-5-nano");
            assertThat(s.attr(GenAiAttributes.RESPONSE_MODEL)).isEqualTo(RESPONSE_MODEL);
            assertThat(s.attr(GenAiAttributes.USAGE_INPUT_TOKENS)).isEqualTo(120L);
            assertThat(s.attr(GenAiAttributes.USAGE_OUTPUT_TOKENS)).isEqualTo(30L);
            assertThat(s.attr(GenAiAttributes.PROVIDER_NAME)).isEqualTo("openai");
        });
        assertThat(chatTrace).hasSize(7);
        // GenAI spans carry the AO session key; no duplicate HTTP span for the OpenAI call, no ORM / JDBC layers.
        assertThat(chatTrace).filteredOn(s -> s.attr(GenAiAttributes.OPERATION_NAME) != null)
                .hasSize(6).allSatisfy(s -> assertThat(s.attr(GenAiAttributes.CONVERSATION_ID)).isEqualTo(conversation));
        assertThat(otlp.spans()).noneMatch(s -> s.scope().contains("java-http-client")
                || s.scope().contains("hibernate") || s.scope().contains("spring-data") || s.scope().contains("jdbc"));
        // Every exported trace starts at an HTTP request: no orphan startup traces (schema / seed SQL).
        assertThat(otlp.spans()).filteredOn(ReceivedSpan::isRoot)
                .allSatisfy(s -> assertThat(s.kind()).isEqualTo("SPAN_KIND_SERVER"));
        assertThat(otlp.spans()).noneMatch(s -> "control".equals(s.attr(GenAiAttributes.SPAN_KIND)));

        // The UI confirmation: its own trace, same AO session as the chat that created the draft.
        ReceivedSpan confirmHttp = single(confirmSpans, ReceivedSpan::isRoot);
        assertThat(confirmHttp.name()).isEqualTo("POST /api/payments/drafts/{draftId}/confirm");
        ReceivedSpan confirmStep = single(confirmSpans, s -> s.name().equals("execute_tool payment.confirm"));
        assertThat(confirmStep.parentSpanId()).isEqualTo(confirmHttp.spanId());
        assertThat(confirmStep.attr(GenAiAttributes.CONVERSATION_ID)).isEqualTo(conversation);
        assertThat(confirmStep.attr(GenAiAttributes.BANK_OUTCOME)).isEqualTo("EXECUTED");

        assertNothingSensitiveExported();
        report("observe-on-control-off.txt");
        assertThat(get("/api/status").path("observability").path("otelJavaAgentActive").asBoolean()).isTrue();
        assertThat(Files.readString(log)).contains("opentelemetry-javaagent - version: "
                + System.getProperty("bank.it.otelAgentVersion"));
    }

    @Test
    void observeOnControlOn_controlSpansNestUnderTheCheckedStepInTheSameExport() throws Exception {
        // Also proves the documented opt-in: the standard variable re-enables JDBC, and SQL nests under the tool.
        start(true, true, Map.of("OTEL_INSTRUMENTATION_JDBC_ENABLED", "true"));

        JsonNode balance = chat(null, "我现在的活期账户余额是多少？");
        List<ReceivedSpan> trace = awaitTrace(balance.path("traceId").asText());

        ReceivedSpan tool = single(trace, s -> s.name().equals("execute_tool getAccountBalance"));
        ReceivedSpan control = single(trace, s -> "control".equals(s.attr(GenAiAttributes.SPAN_KIND)));
        assertThat(control.name()).isEqualTo("bank-java-p0-observe-balance");
        assertThat(control.parentSpanId()).isEqualTo(tool.spanId());
        assertThat(control.attr(GenAiAttributes.OPERATION_NAME)).isEqualTo("control");
        assertThat(control.attr(GenAiAttributes.CONTROL_CHECK_STAGE)).isEqualTo("pre");
        assertThat(control.attr(GenAiAttributes.CONTROL_APPLIES_TO)).isEqualTo("tool_call");
        assertThat(control.attr(GenAiAttributes.CONTROL_ACTION)).isEqualTo("observe");
        assertThat(control.attr(GenAiAttributes.CONTROL_MATCHED)).isEqualTo(true);
        assertThat(trace).filteredOn(s -> s.parentSpanId().equals(tool.spanId()) && s.scope().equals("io.opentelemetry.jdbc"))
                .isNotEmpty().allSatisfy(s -> {
                    assertThat(s.kind()).isEqualTo("SPAN_KIND_CLIENT");
                    assertThat(String.valueOf(s.attr("db.query.text")) + s.attr("db.statement")).doesNotContain("CUST-DEMO");
                });
        // The evaluation request ran inside the tool span's context (same trace ID as the exported tree).
        assertThat(mocks.requests("POST", "/ao/agent-control/api/v1/evaluation"))
                .anySatisfy(r -> assertThat(r.header("X-Trace-Id")).isEqualTo(tool.traceId()));
        assertThat(balance.path("controlDecisions").findValuesAsText("action")).contains("OBSERVE");
        assertThat(otlp.exports()).allSatisfy(e -> assertThat(e.path()).isEqualTo("/v2/trace/otlp"));
        assertNothingSensitiveExported();
        report("observe-on-control-on.txt");
    }

    @Test
    void observeOffControlOn_enforcesWithoutAgentAndWithoutAnyExport() throws Exception {
        start(false, true, Map.of());

        JsonNode balance = chat(null, "我现在的活期账户余额是多少？");

        assertThat(balance.path("controlDecisions").findValuesAsText("action")).contains("OBSERVE");
        assertThat(balance.path("traceId").isMissingNode() || balance.path("traceId").isNull()).isTrue();
        JsonNode status = get("/api/status");
        assertThat(status.path("observability").path("otelJavaAgentActive").asBoolean()).isFalse();
        assertThat(status.path("control").path("eventChannel").asText()).startsWith("EVENT_INGEST_UNAVAILABLE");
        assertThat(mocks.requests("POST", "/ao/agent-control/api/v1/evaluation")).isNotEmpty()
                .allSatisfy(r -> assertThat(r.header("X-Trace-Id")).isNull());
        Thread.sleep(1500);
        assertThat(otlp.exports()).isEmpty();
    }

    @Test
    void observeOffControlOff_plainApplicationWithoutAnyBackendTraffic() throws Exception {
        start(false, false, Map.of());

        JsonNode balance = chat(null, "我现在的活期账户余额是多少？");

        assertThat(balance.path("answer").asText()).contains("52,368.50");
        assertThat(balance.path("controlDecisions").size()).isZero();
        assertThat(balance.path("traceId").isMissingNode() || balance.path("traceId").isNull()).isTrue();
        Thread.sleep(1500);
        assertThat(otlp.exports()).isEmpty();
        assertThat(mocks.requests()).allSatisfy(r -> assertThat(r.path()).isEqualTo("/v1/chat/completions"));
    }

    // ---- application process -----------------------------------------------------------------------------

    private void start(boolean observe, boolean control, Map<String, String> extraEnv) throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        base = "http://127.0.0.1:" + port;
        dataDir = Files.createTempDirectory("bank-agent-it-");
        log = dataDir.resolve("app.log");
        Map<String, String> galileo = new LinkedHashMap<>();
        galileo.put("OBSERVABILITY_ENABLED", String.valueOf(observe));
        galileo.put("CONTROL_MODE", control ? "remote-api" : "off");
        galileo.put("AO_REALM", "au0");
        galileo.put("AO_O11Y_TOKEN", AO_TOKEN);
        galileo.put("AO_API_URL", mocks.baseUrl() + "/ao/api");
        galileo.put("AO_OTLP_ENDPOINT", otlp.baseUrl() + "/v2/trace/otlp");
        galileo.put("AGENT_CONTROL_URL", mocks.baseUrl() + "/ao/agent-control");

        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java")
                .toString(), "-Dfile.encoding=UTF-8"));
        ProcessBuilder builder = new ProcessBuilder();
        Map<String, String> env = builder.environment();
        env.keySet().removeIf(k -> k.startsWith("OTEL_") || k.startsWith("AO_") || k.startsWith("AGENT_CONTROL_")
                || k.startsWith("CONTROL_") || k.startsWith("OPENAI_") || k.equals("JAVA_TOOL_OPTIONS")
                || k.equals("OBSERVABILITY_ENABLED") || k.equals("DEMO_TRACE_CONTENT"));
        env.putAll(galileo);
        env.put("BANK_AGENT_HOST", "127.0.0.1");
        env.put("BANK_AGENT_PORT", String.valueOf(port));
        env.put("BANK_AGENT_DATA_DIR", dataDir.toString());
        env.put("BANK_AGENT_SECRETS_FILE", "/nonexistent/bank-agent-it-secrets.properties");
        env.put("BANK_AGENT_GALILEO_FILE", "/nonexistent/bank-agent-it-galileo.properties");
        env.put("OPENAI_API_KEY", OPENAI_KEY);
        env.put("OPENAI_BASE_URL", mocks.baseUrl() + "/v1");
        env.put("OPENAI_MAX_RETRIES", "0");
        if (observe) {
            // Exactly what scripts/run.sh --observe adds (GalileoCli otel-env) ...
            env.putAll(AoEndpointResolver.resolve(AoRawProperties.fromKeys(galileo)).otelAgentEnvironment());
            // ... plus a short batch delay so the test does not wait for the default 5 s export interval.
            env.put("OTEL_BSP_SCHEDULE_DELAY", "200");
            env.putAll(extraEnv);
            command.add("-javaagent:" + System.getProperty("bank.it.otelAgentJar"));
            command.add("-Dotel.javaagent.configuration-file="
                    + Path.of(System.getProperty("bank.it.projectDir"), "config", "otel-javaagent.properties"));
        }
        command.addAll(List.of("-jar", System.getProperty("bank.it.appJar")));
        app = builder.command(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();

        Instant deadline = Instant.now().plusSeconds(120);
        while (Instant.now().isBefore(deadline)) {
            if (!app.isAlive()) {
                throw new AssertionError("application exited during startup:\n" + tail());
            }
            try {
                if (HTTP.send(HttpRequest.newBuilder(URI.create(base + "/actuator/health")).timeout(Duration.ofSeconds(2))
                        .build(), HttpResponse.BodyHandlers.discarding()).statusCode() == 200) {
                    return;
                }
            } catch (IOException notYet) {
                // not listening yet
            }
            Thread.sleep(500);
        }
        throw new AssertionError("application did not start within 120 s:\n" + tail());
    }

    private String tail() {
        try {
            List<String> lines = Files.readAllLines(log);
            return String.join("\n", lines.subList(Math.max(0, lines.size() - 60), lines.size()));
        } catch (IOException e) {
            return "(no log)";
        }
    }

    private JsonNode chat(String conversationId, String message) throws Exception {
        ObjectNode body = JSON.createObjectNode().put("message", message);
        if (conversationId != null) {
            body.put("conversationId", conversationId);
        }
        HttpResponse<String> response = post("/api/chat", body.toString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    private HttpResponse<String> post(String path, String json) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(60))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private JsonNode get(String path) throws Exception {
        return JSON.readTree(HTTP.send(HttpRequest.newBuilder(URI.create(base + path)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).body());
    }

    /** Waits until the agent has exported the whole trace (its root HTTP span ends last). */
    private List<ReceivedSpan> awaitTrace(String traceId) throws InterruptedException {
        assertThat(traceId).isNotBlank();
        Instant deadline = Instant.now().plusSeconds(20);
        while (Instant.now().isBefore(deadline)) {
            List<ReceivedSpan> trace = otlp.spans().stream().filter(s -> s.traceId().equals(traceId)).toList();
            if (trace.stream().anyMatch(ReceivedSpan::isRoot)) {
                Thread.sleep(300);
                return otlp.spans().stream().filter(s -> s.traceId().equals(traceId)).toList();
            }
            Thread.sleep(200);
        }
        throw new AssertionError("trace " + traceId + " was not exported; received " + otlp.spans().size()
                + " spans\n" + tail());
    }

    private void assertNothingSensitiveExported() {
        assertThat(otlp.spans()).allSatisfy(s -> {
            String exported = s.name() + s.attributes() + s.events() + s.resource();
            assertThat(exported).doesNotContain(AO_TOKEN).doesNotContain(OPENAI_KEY).doesNotContain("张三")
                    .doesNotContain("52368").doesNotContain("52,368").doesNotContain("活期账户余额是多少");
            assertThat(s.events()).noneMatch(e -> e.containsKey("exception.stacktrace"));
        });
    }

    /** Writes every exported trace as an indented tree to target/otel-javaagent-it/ (evidence for the docs). */
    private void report(String file) throws IOException {
        Map<String, List<ReceivedSpan>> traces = new LinkedHashMap<>();
        otlp.spans().forEach(s -> traces.computeIfAbsent(s.traceId(), k -> new ArrayList<>()).add(s));
        StringBuilder out = new StringBuilder("# Spans exported by the OpenTelemetry Java agent "
                + System.getProperty("bank.it.otelAgentVersion") + " (" + traces.size() + " traces, "
                + otlp.spans().size() + " spans, " + otlp.exports().size() + " OTLP requests to "
                + otlp.exports().get(0).path() + ")\n");
        traces.forEach((id, spans) -> {
            out.append("\ntrace ").append(id).append('\n');
            spans.stream().filter(s -> spans.stream().noneMatch(p -> p.spanId().equals(s.parentSpanId())))
                    .forEach(root -> tree(out, spans, root, 1));
        });
        Path dir = Path.of(System.getProperty("bank.it.reportDir", "target/otel-javaagent-it"));
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(file), out);
    }

    private static void tree(StringBuilder out, List<ReceivedSpan> spans, ReceivedSpan span, int depth) {
        StringBuilder line = new StringBuilder("  ".repeat(depth)).append(span.name()).append("  [")
                .append(span.scope()).append(", ").append(span.kind().replace("SPAN_KIND_", "")).append(']');
        for (String key : List.of(GenAiAttributes.OPERATION_NAME, GenAiAttributes.USAGE_INPUT_TOKENS,
                GenAiAttributes.USAGE_OUTPUT_TOKENS, GenAiAttributes.SPAN_KIND, GenAiAttributes.CONTROL_CHECK_STAGE)) {
            if (span.attr(key) != null) {
                line.append(' ').append(key).append('=').append(span.attr(key));
            }
        }
        if (span.attr(GenAiAttributes.CONVERSATION_ID) != null) {
            line.append(" gen_ai.conversation.id=<set>");
        }
        out.append(line).append('\n');
        spans.stream().filter(c -> c.parentSpanId().equals(span.spanId()))
                .forEach(child -> tree(out, spans, child, depth + 1));
    }

    private static ReceivedSpan single(List<ReceivedSpan> spans, Predicate<ReceivedSpan> filter) {
        List<ReceivedSpan> found = spans.stream().filter(filter).toList();
        assertThat(found).as("spans: " + spans.stream().map(ReceivedSpan::name).toList()).hasSize(1);
        return found.get(0);
    }

    private static String match(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        assertThat(m.find()).as(regex).isTrue();
        return m.group(1);
    }

    private static String fixture(String name) {
        try (InputStream in = OtelJavaAgentIT.class.getResourceAsStream("/agent-control/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- OpenAI mock --------------------------------------------------------------------------------------

    /** Chat Completions mock: router JSON, then one tool call, then the final text - with fixed token usage. */
    private static MockHttpServer.Response openAi(MockHttpServer.Request request) {
        try {
            JsonNode body = JSON.readTree(request.body());
            JsonNode messages = body.path("messages");
            JsonNode last = messages.get(messages.size() - 1);
            String router = body.path("response_format").path("json_schema").path("name").asText();
            if ("supervisor_routing".equals(router)) {
                boolean transfer = text(last).contains("转账");
                return completion(transfer
                        ? "{\"intent\":\"AGENTS\",\"steps\":[{\"agent\":\"PAYMENTS\",\"task\":\"向张三转账500元\"}],\"reply\":\"\"}"
                        : "{\"intent\":\"AGENTS\",\"steps\":[{\"agent\":\"ACCOUNT\",\"task\":\"查询活期余额\"}],\"reply\":\"\"}",
                        null, null);
            }
            boolean payments = body.path("tools").toString().contains("createTransferDraft");
            if ("tool".equals(last.path("role").asText())) {
                return completion(payments ? "已为张三创建 500 元的转账草稿，请点击确认。" : "您的活期账户余额为 52,368.50 元。",
                        null, null);
            }
            return payments ? completion(null, "createTransferDraft", "{\"beneficiary\":\"张三\",\"amount\":\"500\"}")
                    : completion(null, "getAccountBalance", "{\"accountType\":\"CHECKING\"}");
        } catch (IOException e) {
            return MockHttpServer.Response.json(400, "{\"error\":\"bad request\"}");
        }
    }

    private static String text(JsonNode message) {
        JsonNode content = message.path("content");
        if (content.isTextual()) {
            return content.asText();
        }
        StringBuilder sb = new StringBuilder();
        content.forEach(part -> sb.append(part.path("text").asText("")));
        return sb.toString();
    }

    private static MockHttpServer.Response completion(String content, String tool, String arguments) {
        ObjectNode root = JSON.createObjectNode().put("id", "chatcmpl-it").put("object", "chat.completion")
                .put("created", 1762700000L).put("model", RESPONSE_MODEL);
        ObjectNode choice = root.putArray("choices").addObject().put("index", 0);
        ObjectNode message = choice.putObject("message").put("role", "assistant");
        if (tool == null) {
            message.put("content", content);
            choice.put("finish_reason", "stop");
        } else {
            message.putNull("content");
            ObjectNode call = message.putArray("tool_calls").addObject().put("id", "call_" + tool)
                    .put("type", "function");
            call.putObject("function").put("name", tool).put("arguments", arguments);
            choice.put("finish_reason", "tool_calls");
        }
        root.putObject("usage").put("prompt_tokens", 120).put("completion_tokens", 30).put("total_tokens", 150);
        return MockHttpServer.Response.json(200, root.toString());
    }
}
