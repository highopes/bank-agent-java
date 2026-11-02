// Phase 2 P0 probe (local use only): Java 21 HttpClient -> Splunk AO SaaS Agent Control REST API.
// Steps: health -> initAgent (idempotent overwrite) -> runtime-token-exchange -> evaluation PRE/POST.
// Run with tools/p0/run-probe.sh ControlProbe. Credentials come from environment variables only and are
// never printed (the runtime token is reported by length only).
//
// Protocol source: agent-control-sdk 8.11.0 (client.py, agents.py, evaluation.py) + Splunk AO docs
// "Initialize and configure Agent Control" (SaaS: AGENT_CONTROL_URL=https://<realm-host>/ao/agent-control,
// AGENT_CONTROL_API_KEY_HEADER=X-SF-Token, AGENT_CONTROL_RUNTIME_TOKEN_HEADER=X-Agent-Control-Runtime-Token).

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

public class ControlProbe {

    static final ObjectMapper JSON = new ObjectMapper();
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public static void main(String[] args) throws Exception {
        String realm = require("AO_REALM");
        String token = require("AO_O11Y_TOKEN");
        String base = System.getProperty("probe.controlUrl",
                "https://app." + realm + ".observability.splunkcloud.com/ao/agent-control");
        String targetId = System.getProperty("probe.targetId");
        if (targetId == null || targetId.isBlank()) {
            throw new IllegalStateException("-Dprobe.targetId=<agent stream uuid> is required");
        }
        String agent = System.getProperty("probe.agent", "bank-agent-java");

        Result health = call("GET", base + "/health", null, token, null);
        System.out.println("health HTTP " + health.status + " " + health.body);

        ObjectNode init = JSON.createObjectNode();
        init.putObject("agent").put("agent_name", agent)
                .put("agent_description", "Bank Agent Java demo (Java P0 probe)").put("agent_version", "0.2.0-p0");
        ArrayNode steps = init.putArray("steps");
        for (String llm : List.of("banking-assistant", "supervisor-router", "account-agent", "transactions-agent",
                "payments-agent")) {
            steps.addObject().put("type", "llm").put("name", llm);
        }
        for (String tool : List.of("getAccountBalance", "getAccountSummary", "listBeneficiaries", "listPaymentMethods",
                "listRecentTransactions", "searchTransactions", "summarizeSpending", "listTransferBeneficiaries",
                "validateTransfer", "createTransferDraft", "cancelPendingTransfer", "getRecentTransferActivity",
                "payment.confirm")) {
            steps.addObject().put("type", "tool").put("name", tool);
        }
        init.put("conflict_mode", "overwrite").put("target_type", "log_stream").put("target_id", targetId);
        long t0 = System.nanoTime();
        Result reg = call("POST", base + "/api/v1/agents/initAgent", init, token, null);
        JsonNode regBody = JSON.readTree(reg.body);
        System.out.println("initAgent HTTP " + reg.status + " created=" + regBody.path("created")
                + " controls=" + regBody.path("controls").size() + " ms=" + ms(t0));

        t0 = System.nanoTime();
        ObjectNode ex = JSON.createObjectNode().put("target_type", "log_stream").put("target_id", targetId);
        Result exchange = call("POST", base + "/api/v1/auth/runtime-token-exchange", ex, token, null);
        JsonNode exBody = JSON.readTree(exchange.body);
        String runtime = exBody.path("token").asText();
        System.out.println("runtime-token-exchange HTTP " + exchange.status + " tokenLength=" + runtime.length()
                + " expires_at=" + exBody.path("expires_at").asText() + " scopes=" + exBody.path("scopes")
                + " ms=" + ms(t0));

        evaluate(base, token, runtime, agent, targetId, "pre", "tool", "getAccountBalance",
                JSON.createObjectNode().put("accountType", "CHECKING"), null);
        evaluate(base, token, runtime, agent, targetId, "pre", "tool", "p0-deny-probe",
                JSON.createObjectNode().put("beneficiary", "BLOCKED-BEN").put("amount", "1"), null);
        evaluate(base, token, runtime, agent, targetId, "post", "llm", "p0-steer-probe",
                JSON.getNodeFactory().textNode("question"), JSON.getNodeFactory().textNode("Your balance is 100"));
        evaluate(base, token, runtime, agent, targetId, "pre", "tool", "listBeneficiaries",
                JSON.createObjectNode(), null);
    }

    static void evaluate(String base, String token, String runtime, String agent, String targetId, String stage,
                         String type, String name, JsonNode input, JsonNode output) throws Exception {
        ObjectNode req = JSON.createObjectNode().put("agent_name", agent).put("stage", stage)
                .put("target_type", "log_stream").put("target_id", targetId);
        ObjectNode step = req.putObject("step").put("type", type).put("name", name);
        step.set("input", input);
        if (output != null) {
            step.set("output", output);
        }
        long t0 = System.nanoTime();
        Result r = call("POST", base + "/api/v1/evaluation", req, token, runtime);
        JsonNode body = JSON.readTree(r.body);
        StringBuilder decisions = new StringBuilder();
        for (JsonNode m : body.path("matches")) {
            decisions.append(m.path("action").asText()).append('(').append(m.path("control_id").asLong()).append(':')
                    .append(m.path("control_name").asText()).append(" evaluator=")
                    .append(m.path("result").path("metadata").path("condition_trace").path("evaluator_name").asText())
                    .append(" steer=").append(m.path("steering_context").path("message").asText("-")).append(") ");
        }
        System.out.println("evaluate " + stage + " " + type + ":" + name + " HTTP " + r.status + " is_safe="
                + body.path("is_safe") + " matches=" + body.path("matches").size() + " non_matches="
                + body.path("non_matches").size() + " errors=" + body.path("errors").size() + " " + decisions
                + "ms=" + ms(t0));
    }

    record Result(int status, String body) {
    }

    static Result call(String method, String url, JsonNode json, String token, String runtime) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
                .header("X-SF-Token", token).header("Accept", "application/json")
                .header("X-Agent-Control-SDK", "bank-agent-java-p0");
        if (runtime != null) {
            b.header("X-Agent-Control-Runtime-Token", runtime);
        }
        if (json != null) {
            b.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(json)));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<String> response = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Result(response.statusCode(), response.body());
    }

    static long ms(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }

    static String require(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException(name + " is not set");
        }
        return v;
    }
}
