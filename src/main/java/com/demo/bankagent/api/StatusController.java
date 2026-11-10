package com.demo.bankagent.api;

import com.demo.bankagent.config.BankAgentProperties;
import com.demo.bankagent.control.ControlEventReporter;
import com.demo.bankagent.control.ControlGateway;
import com.demo.bankagent.control.RemoteAgentControlGateway;
import com.demo.bankagent.conversation.ConversationService;
import com.demo.bankagent.observability.AoSettings;
import com.demo.bankagent.observability.AoTargetHolder;
import com.demo.bankagent.observability.GenAiSpans;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Non-sensitive runtime status for the UI. No API key, token or credential value is ever exposed. */
@RestController
public class StatusController {

    private final BankAgentProperties properties;
    private final ConversationService conversationService;
    private final AoSettings ao;
    private final AoTargetHolder targets;
    private final GenAiSpans spans;
    private final ControlGateway controlGateway;
    private final ControlEventReporter controlEvents;

    public StatusController(BankAgentProperties properties, ConversationService conversationService, AoSettings ao,
                            AoTargetHolder targets, GenAiSpans spans, ControlGateway controlGateway,
                            ControlEventReporter controlEvents) {
        this.properties = properties;
        this.conversationService = conversationService;
        this.ao = ao;
        this.targets = targets;
        this.spans = spans;
        this.controlGateway = controlGateway;
        this.controlEvents = controlEvents;
    }

    @GetMapping("/api/status")
    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("application", "bank-agent-java");
        status.put("features", "Observe (OpenTelemetry Java agent + LangChain4j listeners) + Agent Control (each switchable)");
        status.put("llmProvider", "OpenAI");
        status.put("llmConfigured", properties.openai().isConfigured());
        status.put("model", properties.openai().model());
        status.put("reasoningEffort", properties.openai().reasoningEffort());
        status.put("activeConversations", conversationService.activeCount());
        status.put("singleTransferLimit", properties.payments().singleTransferLimit());
        status.put("draftTtlMinutes", properties.payments().draftTtl().toMinutes());
        status.put("timeZone", properties.demo().timeZone());
        status.put("javaVersion", Runtime.version().toString());
        status.put("observability", observability());
        status.put("control", control());
        return status;
    }

    private Map<String, Object> observability() {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("enabled", ao.observabilityEnabled());
        // True only when the OpenTelemetry Java agent is attached and spans are really recorded and exported.
        o.put("otelJavaAgentActive", spans.active());
        o.put("profile", ao.profile().key());
        o.put("demoTraceContent", ao.demoTraceContent());
        if (ao.active()) {
            o.put("otlpHost", AoSettings.host(ao.otlpEndpoint().value()));
            o.put("project", ao.projectName().value());
            o.put("agentStream", ao.agentStream().value());
            o.put("targetState", targets.state().name());
            targets.target().ifPresent(t -> {
                o.put("projectId", t.projectId());
                o.put("agentStreamId", t.agentStreamId());
            });
            targets.consoleStreamUrl().ifPresent(url -> o.put("consoleStreamUrl", url));
            targets.error().ifPresent(e -> o.put("targetError", e));
        }
        return o;
    }

    private Map<String, Object> control() {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("mode", ao.controlMode().key());
        c.put("gateway", controlGateway.describe());
        c.put("eventChannel", controlEvents.eventChannel());
        if (ao.controlEnabled()) {
            c.put("agentName", ao.agentControlAgentName().value());
            c.put("readFailureMode", ao.readFailureMode().value().key());
            c.put("writeFailureMode", ao.writeFailureMode().value().key());
            c.put("maxSteerRetries", ao.maxSteerRetries().value());
            if (controlGateway instanceof RemoteAgentControlGateway remote) {
                c.put("boundControls", remote.boundControlSummaries());
            }
        }
        return c;
    }
}
