package com.demo.bankagent.control;

import com.demo.bankagent.control.ControlMatchResult.Category;
import com.demo.bankagent.observability.AoSettings;
import com.demo.bankagent.observability.AoTarget;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pure Java 21 client of the Agent Control server API of Splunk AO SaaS (no Python/TypeScript sidecar). Every
 * route below was verified against the real au0 SaaS in P0 (docs/PHASE2-PROTOCOL-VERIFICATION.md); request and
 * response shapes follow agent-control-sdk 8.11 / agent-control-models:
 * <ul>
 *   <li>GET  {AGENT_CONTROL_URL}/health</li>
 *   <li>POST {AGENT_CONTROL_URL}/api/v1/agents/initAgent - idempotent registration of agent + step catalog,
 *       bound to target (log_stream, Agent Stream UUID), conflict_mode=overwrite</li>
 *   <li>POST {AGENT_CONTROL_URL}/api/v1/auth/runtime-token-exchange - target-bound JWT (~5 min), cached and
 *       refreshed before expiry; sent raw on X-Agent-Control-Runtime-Token (SaaS rejects evaluation without it)</li>
 *   <li>GET  {AGENT_CONTROL_URL}/api/v1/agents/{agent}/controls?target_type&amp;target_id - effective controls,
 *       used only as a scope prefilter like the official SDK (re-read every controls-refresh-interval)</li>
 *   <li>POST {AGENT_CONTROL_URL}{AGENT_CONTROL_EVALUATION_PATH} - server-side evaluation (execution=server)</li>
 * </ul>
 * The API credential header (X-SF-Token / Galileo-API-Key) is sent on every request, as the SDK does when the
 * runtime token uses a dedicated header. Secrets never appear in logs or exception messages.
 */
public class RemoteAgentControlGateway implements ControlGateway {

    private static final Logger log = LoggerFactory.getLogger(RemoteAgentControlGateway.class);

    private final AoSettings settings;
    private final AoTarget target;
    private final ControlProperties props;
    private final ObjectMapper mapper;
    private final StepCatalog catalog;
    private final HttpClient http;
    private final String baseUrl;
    private final String version;

    private final Object tokenLock = new Object();
    private volatile String runtimeToken;
    private volatile Instant runtimeTokenExpiresAt = Instant.EPOCH;

    private final Object controlsLock = new Object();
    private volatile List<BoundControl> controls;
    private volatile Instant controlsFetchedAt = Instant.EPOCH;
    private volatile Instant lastCatalogWarning = Instant.EPOCH;
    private volatile String serverVersion = "?";
    private volatile long lastLatencyMs = -1;

    /** A control bound to this agent/target, reduced to the fields needed for scope prefiltering. */
    record BoundControl(long id, String name, boolean enabled, boolean rendered, String execution, String action,
                        List<String> stepTypes, List<String> stepNames, String stepNameRegex, List<String> stages) {

        boolean appliesTo(ControlStage stage, ControlStep step) {
            if (!enabled || !rendered) {
                return false;
            }
            if (stages != null && !stages.contains(stage.wire())) {
                return false;
            }
            if (stepTypes != null && !stepTypes.contains(step.type())) {
                return false;
            }
            if (stepNames == null && stepNameRegex == null) {
                return true;
            }
            if (stepNames != null && stepNames.contains(step.name())) {
                return true;
            }
            if (stepNameRegex != null) {
                try {
                    return Pattern.compile(stepNameRegex).matcher(step.name()).find();
                } catch (PatternSyntaxException e) {
                    return true; // cannot prefilter: let the server decide
                }
            }
            return false;
        }
    }

    public RemoteAgentControlGateway(AoSettings settings, AoTarget target, ControlProperties props,
                                     ObjectMapper mapper, StepCatalog catalog, String version) {
        this.settings = settings;
        this.target = target;
        this.props = props;
        this.mapper = mapper;
        this.catalog = catalog;
        this.version = version;
        this.baseUrl = settings.agentControlUrl().value();
        this.http = HttpClient.newBuilder().connectTimeout(props.connectTimeout()).build();
    }

    /** Startup handshake; any failure is fatal for CONTROL_MODE=remote-api (see ControlConfiguration). */
    public void start() {
        JsonNode health = withAttempts("health", () -> send("GET", "/health", null, false));
        serverVersion = health.path("version").asText("?");
        ObjectNode init = mapper.createObjectNode();
        init.putObject("agent")
                .put("agent_name", agentName())
                .put("agent_description", "Bank Agent Java demo (Spring Boot + LangChain4j), Java REST adapter")
                .put("agent_version", version);
        ArrayNode steps = init.putArray("steps");
        for (StepCatalog.StepDescriptor step : catalog.steps()) {
            ObjectNode s = steps.addObject().put("type", step.type()).put("name", step.name());
            if (step.description() != null) {
                s.put("description", step.description());
            }
        }
        init.put("conflict_mode", "overwrite")
                .put("target_type", targetType())
                .put("target_id", target.agentStreamId());
        JsonNode registered = withAttempts("initAgent", () -> send("POST", "/api/v1/agents/initAgent", init, false));
        if (usesRuntimeToken()) {
            token(true);
        }
        refreshControls(true);
        log.info("Agent Control ready: server {} at {}, agent '{}' {} with {} steps, target {}:{}, {} bound control(s){}",
                serverVersion, AoSettings.host(baseUrl), agentName(),
                registered.path("created").asBoolean(false) ? "registered" : "already registered (overwrite)",
                catalog.steps().size(), targetType(), target.agentStreamId(),
                controls == null ? "?" : controls.size(), usesRuntimeToken() ? ", runtime token exchange OK" : "");
        if (controls != null) {
            controls.stream().filter(c -> c.enabled() && c.rendered() && !"server".equals(c.execution()))
                    .forEach(c -> log.warn("Control '{}' uses execution={}; this Java adapter only supports "
                            + "server-side execution. Steps in its scope are reported as UNSUPPORTED_EXECUTION and "
                            + "handled with the failure mode.", c.name(), c.execution()));
        }
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public String describe() {
        List<BoundControl> current = controls;
        return "remote-api: server " + serverVersion + " at " + AoSettings.host(baseUrl) + ", agent '" + agentName()
                + "', target " + targetType() + ":" + target.agentStreamId() + ", bound controls "
                + (current == null ? "unknown" : current.size()) + ", last evaluation latency "
                + (lastLatencyMs < 0 ? "-" : lastLatencyMs + " ms");
    }

    /** Names and actions of the currently bound controls (for /api/status); refreshes if stale. */
    public List<String> boundControlSummaries() {
        List<BoundControl> current = currentControls();
        if (current == null) {
            return List.of();
        }
        return current.stream().map(c -> c.name() + " [" + c.action() + ", " + c.execution()
                + (c.enabled() ? "" : ", disabled") + "]").toList();
    }

    @Override
    public ControlDecision evaluate(ControlStage stage, ControlStep step) {
        long start = System.nanoTime();
        if (props.prefilter()) {
            List<BoundControl> current = currentControls();
            if (current != null) {
                List<BoundControl> applicable = current.stream().filter(c -> c.appliesTo(stage, step)).toList();
                if (applicable.isEmpty()) {
                    return ControlDecision.skipped("no bound control applies to " + stage.wire() + " "
                            + step.type() + ":" + step.name(), elapsed(start));
                }
                List<BoundControl> unsupported = applicable.stream()
                        .filter(c -> !"server".equals(c.execution())).toList();
                if (!unsupported.isEmpty()) {
                    return ControlDecision.unavailable("UNSUPPORTED_EXECUTION", "control(s) "
                            + unsupported.stream().map(BoundControl::name).toList()
                            + " use execution=sdk, which this Java adapter does not run locally", List.of(),
                            elapsed(start));
                }
            }
        }
        ObjectNode request = mapper.createObjectNode()
                .put("agent_name", agentName())
                .put("stage", stage.wire())
                .put("target_type", targetType())
                .put("target_id", target.agentStreamId());
        ObjectNode stepNode = request.putObject("step").put("type", step.type()).put("name", step.name());
        stepNode.set("input", step.input());
        if (step.output() != null) {
            stepNode.set("output", step.output());
        }
        ObjectNode context = stepNode.putObject("context").put("source", "bank-agent-java");
        if (step.conversationId() != null) {
            context.put("conversation_id", step.conversationId());
        }
        JsonNode body = withAttempts("evaluation", () -> send("POST",
                settings.agentControlEvaluationPath().value(), request, true));
        long latency = elapsed(start);
        lastLatencyMs = latency;
        return mapResponse(body, latency);
    }

    // ---- response mapping ------------------------------------------------------------------------

    /**
     * Maps an EvaluationResponse with the SDK priority deny &gt; steer &gt; observe. Differences, all fail-safe:
     * an unknown action is treated as DENY; is_safe=false without an actionable match is treated as DENY; evaluator
     * errors (the SDK always blocks) become UNAVAILABLE so that the configured failure mode decides.
     */
    static ControlDecision mapResponse(JsonNode body, long latencyMs) {
        if (body == null || !body.isObject() || !body.path("is_safe").isBoolean()) {
            throw new ControlTransportException("CONTRACT", 200, "evaluation response has no boolean is_safe");
        }
        boolean isSafe = body.get("is_safe").booleanValue();
        List<ControlMatchResult> matches = parseResults(body.path("matches"), Category.MATCH);
        List<ControlMatchResult> errors = parseResults(body.path("errors"), Category.ERROR);
        List<ControlMatchResult> nonMatches = parseResults(body.path("non_matches"), Category.NON_MATCH);
        List<ControlMatchResult> all = new ArrayList<>(matches);
        all.addAll(errors);
        all.addAll(nonMatches);

        ControlMatchResult deny = first(matches, m -> "deny".equals(m.action()) || !isKnownAction(m.action()));
        if (deny != null) {
            String reason = isKnownAction(deny.action()) ? deny.message()
                    : "unrecognized action '" + deny.action() + "' - blocked for safety";
            return new ControlDecision(ControlDecision.Status.EVALUATED, ControlAction.DENY, reason,
                    deny.controlName(), deny.controlId(), null, all, latencyMs, null);
        }
        if (!errors.isEmpty()) {
            String reason = "evaluator error in " + errors.stream()
                    .map(e -> e.controlName() + (e.errorMessage() == null ? "" : " (" + e.errorMessage() + ")"))
                    .toList();
            return ControlDecision.unavailable("EVALUATOR_ERROR", reason, all, latencyMs);
        }
        ControlMatchResult steer = first(matches, m -> "steer".equals(m.action()));
        if (steer != null) {
            return new ControlDecision(ControlDecision.Status.EVALUATED, ControlAction.STEER, steer.message(),
                    steer.controlName(), steer.controlId(),
                    steer.steeringContext() == null ? steer.message() : steer.steeringContext(), all, latencyMs, null);
        }
        if (!isSafe) {
            return new ControlDecision(ControlDecision.Status.EVALUATED, ControlAction.DENY,
                    "server reported is_safe=false without an actionable match - blocked for safety", null, null,
                    null, all, latencyMs, null);
        }
        ControlMatchResult observe = first(matches, m -> "observe".equals(m.action()));
        if (observe != null) {
            return new ControlDecision(ControlDecision.Status.EVALUATED, ControlAction.OBSERVE, observe.message(),
                    observe.controlName(), observe.controlId(), null, all, latencyMs, null);
        }
        return new ControlDecision(ControlDecision.Status.EVALUATED, ControlAction.ALLOW,
                all.isEmpty() ? "no control applied" : "no control matched", null, null, null, all, latencyMs, null);
    }

    private static List<ControlMatchResult> parseResults(JsonNode array, Category category) {
        List<ControlMatchResult> results = new ArrayList<>();
        if (array == null || array.isMissingNode() || array.isNull()) {
            return results;
        }
        if (!array.isArray()) {
            throw new ControlTransportException("CONTRACT", 200, "evaluation response field is not a list");
        }
        for (JsonNode m : array) {
            JsonNode result = m.path("result");
            if (!m.path("control_id").isNumber() || !m.path("control_name").isTextual()
                    || !m.path("action").isTextual() || !result.isObject() || !result.path("matched").isBoolean()) {
                throw new ControlTransportException("CONTRACT", 200, "evaluation response contains a malformed "
                        + "control result");
            }
            JsonNode trace = result.path("metadata").path("condition_trace");
            JsonNode preview = result.path("metadata").path("engine_selected_data_preview").path("value");
            if (preview.isMissingNode()) {
                preview = result.path("metadata").path("selected_data_preview").path("value");
            }
            JsonNode steering = m.path("steering_context");
            String steeringText = steering.isObject() ? textOrNull(steering.path("message"))
                    : textOrNull(steering);
            results.add(new ControlMatchResult(category, m.path("control_id").asLong(), m.path("control_name").asText(),
                    normalizeAction(m.path("action").asText()), result.path("matched").asBoolean(),
                    result.path("confidence").asDouble(0.0), textOrNull(result.path("message")),
                    textOrNull(result.path("error")), steeringText, textOrNull(trace.path("evaluator_name")),
                    textOrNull(trace.path("selector_path")),
                    preview.isMissingNode() || preview.isNull() ? null
                            : preview.isTextual() ? preview.asText() : preview.toString(),
                    textOrNull(m.path("control_execution_id")),
                    m.path("execution_duration_ms").isNumber() ? m.path("execution_duration_ms").asDouble() : null));
        }
        return results;
    }

    /** agent_control_models.actions.normalize_action: allow/warn/log are legacy aliases of observe. */
    static String normalizeAction(String action) {
        String a = action == null ? "" : action.strip().toLowerCase(Locale.ROOT);
        return switch (a) {
            case "allow", "observe", "warn", "log" -> "observe";
            default -> a;
        };
    }

    private static boolean isKnownAction(String action) {
        return "deny".equals(action) || "steer".equals(action) || "observe".equals(action);
    }

    private static ControlMatchResult first(List<ControlMatchResult> list, Predicate<ControlMatchResult> p) {
        return list.stream().filter(p).findFirst().orElse(null);
    }

    private static String textOrNull(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : node.asText();
    }

    // ---- control catalog (prefilter) ---------------------------------------------------------------

    private List<BoundControl> currentControls() {
        if (Instant.now().isAfter(controlsFetchedAt.plus(props.controlsRefreshInterval()))) {
            refreshControls(false);
        }
        return controls;
    }

    private void refreshControls(boolean startup) {
        synchronized (controlsLock) {
            if (!startup && Instant.now().isBefore(controlsFetchedAt.plus(props.controlsRefreshInterval()))) {
                return;
            }
            try {
                String path = "/api/v1/agents/" + encode(agentName()) + "/controls?target_type="
                        + encode(targetType()) + "&target_id=" + encode(target.agentStreamId());
                JsonNode body = send("GET", path, null, false);
                List<BoundControl> parsed = new ArrayList<>();
                for (JsonNode c : body.path("controls")) {
                    JsonNode def = c.path("control");
                    JsonNode scope = def.path("scope");
                    parsed.add(new BoundControl(c.path("id").asLong(), c.path("name").asText("?"),
                            def.path("enabled").asBoolean(true),
                            !(def.hasNonNull("template") && !def.hasNonNull("condition")),
                            def.path("execution").asText("server"),
                            normalizeAction(def.path("action").path("decision").asText("")),
                            strings(scope.path("step_types")), strings(scope.path("step_names")),
                            textOrNull(scope.path("step_name_regex")), strings(scope.path("stages"))));
                }
                controls = List.copyOf(parsed);
            } catch (RuntimeException e) {
                // Without a fresh catalog every step is sent to the server (no prefilter): safe but slower.
                controls = null;
                if (Instant.now().isAfter(lastCatalogWarning.plusSeconds(60))) {
                    lastCatalogWarning = Instant.now();
                    log.warn("Could not refresh the Agent Control catalog ({}); evaluating every step remotely.",
                            e.getMessage());
                }
            } finally {
                controlsFetchedAt = Instant.now();
            }
        }
    }

    private static List<String> strings(JsonNode node) {
        if (node == null || !node.isArray()) {
            return null;
        }
        List<String> values = new ArrayList<>();
        node.forEach(v -> values.add(v.asText()));
        return List.copyOf(values);
    }

    // ---- runtime token -----------------------------------------------------------------------------

    private boolean usesRuntimeToken() {
        return "jwt".equals(settings.runtimeAuthMode().value());
    }

    private String token(boolean forceRefresh) {
        synchronized (tokenLock) {
            if (!forceRefresh && runtimeToken != null
                    && Instant.now().plus(props.tokenRefreshMargin()).isBefore(runtimeTokenExpiresAt)) {
                return runtimeToken;
            }
            ObjectNode body = mapper.createObjectNode().put("target_type", targetType())
                    .put("target_id", target.agentStreamId());
            JsonNode response = withAttempts("runtime-token-exchange",
                    () -> send("POST", "/api/v1/auth/runtime-token-exchange", body, false));
            String token = response.path("token").asText(null);
            String expires = response.path("expires_at").asText(null);
            if (token == null || token.isBlank() || expires == null
                    || !target.agentStreamId().equals(response.path("target_id").asText())) {
                throw new ControlTransportException("TOKEN", 200, "runtime token exchange returned an unexpected "
                        + "response (missing token/expires_at or a different target)");
            }
            runtimeToken = token;
            runtimeTokenExpiresAt = parseInstant(expires);
            return token;
        }
    }

    private static Instant parseInstant(String text) {
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (RuntimeException e) {
            try {
                return Instant.parse(text.endsWith("Z") ? text : text + "Z");
            } catch (RuntimeException ignored) {
                return Instant.now().plusSeconds(60);
            }
        }
    }

    // ---- HTTP ----------------------------------------------------------------------------------------

    private interface Call {
        JsonNode run();
    }

    /** Retries timeouts, network errors, 429 and 5xx (all requests here are free of business side effects). */
    private JsonNode withAttempts(String what, Call call) {
        int attempts = Math.max(1, what.equals("evaluation") ? props.evaluationAttempts() : props.startupAttempts());
        ControlTransportException last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return call.run();
            } catch (ControlTransportException e) {
                last = e;
                boolean retryable = e.code().equals("TIMEOUT") || e.code().equals("NETWORK")
                        || e.code().equals("SERVER_ERROR") || e.code().equals("RATE_LIMITED");
                if (!retryable || attempt == attempts) {
                    break;
                }
                sleep(200L * attempt);
            }
        }
        throw last;
    }

    private JsonNode send(String method, String path, JsonNode body, boolean runtimeAuth) {
        return send(method, path, body, runtimeAuth, true);
    }

    private JsonNode send(String method, String path, JsonNode body, boolean runtimeAuth, boolean mayRefresh) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(props.requestTimeout())
                .header("Accept", "application/json")
                .header("X-Agent-Control-SDK", "bank-agent-java")
                .header("X-Agent-Control-SDK-Version", version)
                .header(settings.agentControlApiKeyHeader().value(), settings.agentControlApiKey().value());
        if (runtimeAuth && usesRuntimeToken()) {
            String header = settings.runtimeTokenHeader().value();
            String token = token(false);
            builder.header(header, "authorization".equalsIgnoreCase(header) ? "Bearer " + token : token);
            SpanContext span = Span.current().getSpanContext();
            if (span.isValid()) {
                builder.header("X-Trace-Id", span.getTraceId()).header("X-Span-Id", span.getSpanId());
            }
        }
        try {
            if (body == null) {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                builder.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            }
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status / 100 == 2) {
                String text = response.body();
                return text == null || text.isBlank() ? mapper.createObjectNode() : mapper.readTree(text);
            }
            String problem = problemDetail(response.body());
            if ((status == 401 || status == 403) && runtimeAuth && usesRuntimeToken() && mayRefresh
                    && tokenRejected(response, problem)) {
                synchronized (tokenLock) {
                    runtimeToken = null;
                }
                return send(method, path, body, true, false);
            }
            String code = status == 401 || status == 403 ? "AUTH" : status == 429 ? "RATE_LIMITED"
                    : status >= 500 ? "SERVER_ERROR" : "CONTRACT";
            throw new ControlTransportException(code, status, method + " " + pathOnly(path) + " returned HTTP "
                    + status + problem);
        } catch (HttpTimeoutException e) {
            throw new ControlTransportException("TIMEOUT", method + " " + pathOnly(path) + " timed out after "
                    + props.requestTimeout().toMillis() + " ms", e);
        } catch (IOException e) {
            throw new ControlTransportException("NETWORK", method + " " + pathOnly(path) + " failed: "
                    + e.getClass().getSimpleName(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ControlTransportException("NETWORK", method + " " + pathOnly(path) + " interrupted", e);
        }
    }

    private static boolean tokenRejected(HttpResponse<String> response, String problem) {
        String challenge = response.headers().firstValue("WWW-Authenticate").orElse("").toLowerCase(Locale.ROOT);
        return challenge.contains("invalid_token") || problem.toLowerCase(Locale.ROOT).contains("token");
    }

    private String problemDetail(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        try {
            JsonNode node = mapper.readTree(body);
            String code = node.path("error_code").asText("");
            String detail = node.path("detail").isTextual() ? node.path("detail").asText() : "";
            String text = (code + " " + detail).strip();
            return text.isEmpty() ? "" : ": " + (text.length() > 200 ? text.substring(0, 200) + "..." : text);
        } catch (IOException e) {
            return "";
        }
    }

    private String agentName() {
        return settings.agentControlAgentName().value();
    }

    private String targetType() {
        return settings.agentControlTargetType().value();
    }

    private static String pathOnly(String path) {
        int q = path.indexOf('?');
        return q < 0 ? path : path.substring(0, q);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static long elapsed(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
