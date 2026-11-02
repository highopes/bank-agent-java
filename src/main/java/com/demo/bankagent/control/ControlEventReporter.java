package com.demo.bankagent.control;

import com.demo.bankagent.observability.AgentSpanRunner;
import com.demo.bankagent.observability.AoBackendProfile;
import com.demo.bankagent.observability.AoSettings;
import com.demo.bankagent.observability.BankTelemetry;
import com.demo.bankagent.observability.GenAiAttributes;
import com.demo.bankagent.observability.TraceContentPolicy;
import com.demo.bankagent.trace.ControlDecisionTrace;
import com.demo.bankagent.trace.ExecutionTrace;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Reports what Agent Control actually evaluated. It is a separate component from the gateway because the server's
 * /evaluation endpoint is evaluation-only and records no Control event by itself (agent-control
 * evaluation_events.py rebuilds events client-side).
 *
 * <p>Event channel (P0, docs/PHASE2-PROTOCOL-VERIFICATION.md): the official Splunk AO Python SDK turns every
 * ControlExecutionEvent into a "control span" (splunk_ao handlers/agent_control/bridge.py -&gt; add_control_span)
 * and exports it via OTLP with galileo.span.kind=control and agent_control.* attributes
 * (converter/attribute_mapping.set_control_attributes). This class emits the same span, as a child of the
 * evaluated step's span, for every match / non-match / error returned by the server. P0 verified that AO stores it
 * as a native control span and the Console shows it in the span's Controls tab. Without Observe there is no
 * supported channel; that state is reported as EVENT_INGEST_UNAVAILABLE and nothing is faked.
 */
@Component
public class ControlEventReporter {

    private static final Logger log = LoggerFactory.getLogger(ControlEventReporter.class);
    public static final String EVENT_INGEST_UNAVAILABLE = "EVENT_INGEST_UNAVAILABLE";

    private final BankTelemetry telemetry;
    private final AoSettings settings;
    private final TraceContentPolicy content;
    private final ObjectMapper mapper;
    private final AtomicBoolean warnedNoChannel = new AtomicBoolean();

    public ControlEventReporter(BankTelemetry telemetry, AoSettings settings, TraceContentPolicy content,
                                ObjectMapper mapper) {
        this.telemetry = telemetry;
        this.settings = settings;
        this.content = content;
        this.mapper = mapper;
    }

    /** Status text for /api/status and the docs. */
    public String eventChannel() {
        if (!telemetry.exporting()) {
            return EVENT_INGEST_UNAVAILABLE + " (OBSERVABILITY_ENABLED=false: control spans cannot be exported)";
        }
        return settings.profile() == AoBackendProfile.O11Y
                ? "OTLP control spans (verified in P0 for profile o11y)"
                : "OTLP control spans (same contract; not verified online for profile galileo)";
    }

    public void report(ControlStage stage, ControlStep step, ControlDecision decision, String verdict) {
        ExecutionTrace.current().ifPresent(t -> t.addControlDecision(new ControlDecisionTrace(stage.wire(),
                step.type(), step.name(), verdict, decision.action() == null ? null : decision.action().name(),
                decision.controlName(), decision.controlId(), decision.reason(), decision.latencyMs(),
                decision.results().size())));

        Span stepSpan = Span.current();
        String prefix = "bank.control." + stage.wire() + ".";
        stepSpan.setAttribute(prefix + "verdict", verdict);
        stepSpan.setAttribute(prefix + "latency_ms", decision.latencyMs());
        if (decision.controlName() != null) {
            stepSpan.setAttribute(prefix + "control", decision.controlName());
        }
        if (decision.failureCode() != null) {
            stepSpan.setAttribute(prefix + "failure", decision.failureCode());
        }

        if (decision.results().isEmpty()) {
            return;
        }
        if (!telemetry.exporting()) {
            if (warnedNoChannel.compareAndSet(false, true)) {
                log.warn("Agent Control results cannot be shown in the Console Controls tab: {}", eventChannel());
            }
            return;
        }
        for (ControlMatchResult result : decision.results()) {
            emitControlSpan(stage, step, result);
        }
    }

    private void emitControlSpan(ControlStage stage, ControlStep step, ControlMatchResult r) {
        Instant end = Instant.now();
        long durationNanos = r.executionDurationMs() == null ? 0L : (long) (r.executionDurationMs() * 1_000_000);
        Span span = telemetry.tracer().spanBuilder(r.controlName())
                .setParent(Context.current())
                .setSpanKind(SpanKind.INTERNAL)
                .setStartTimestamp(end.minusNanos(durationNanos))
                .startSpan();
        span.setAttribute(GenAiAttributes.SPAN_KIND, "control");
        span.setAttribute(GenAiAttributes.OPERATION_NAME, GenAiAttributes.OP_CONTROL);
        span.setAttribute(GenAiAttributes.CONTROL_ID, r.controlId());
        span.setAttribute(GenAiAttributes.CONTROL_NAME, r.controlName());
        span.setAttribute(GenAiAttributes.CONTROL_AGENT_NAME, settings.agentControlAgentName().value());
        span.setAttribute(GenAiAttributes.CONTROL_CHECK_STAGE, stage.wire());
        span.setAttribute(GenAiAttributes.CONTROL_APPLIES_TO, step.appliesTo());
        span.setAttribute(GenAiAttributes.CONTROL_ACTION, r.action());
        span.setAttribute(GenAiAttributes.CONTROL_MATCHED, r.matched());
        span.setAttribute(GenAiAttributes.CONTROL_CONFIDENCE, r.confidence());
        if (r.evaluatorName() != null) {
            span.setAttribute(GenAiAttributes.CONTROL_EVALUATOR_NAME, r.evaluatorName());
        }
        if (r.selectorPath() != null) {
            span.setAttribute(GenAiAttributes.CONTROL_SELECTOR_PATH, r.selectorPath());
        }
        if (r.controlExecutionId() != null) {
            span.setAttribute(GenAiAttributes.CONTROL_EXECUTION_ID, r.controlExecutionId());
        }
        if (step.conversationId() != null) {
            span.setAttribute(GenAiAttributes.CONVERSATION_ID, step.conversationId());
        }
        span.setAttribute(GenAiAttributes.INPUT_MESSAGES, content.messages("user", r.selectedPreview(), null));
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("action", r.action());
        output.put("matched", r.matched());
        output.put("confidence", r.confidence());
        if (r.errorMessage() != null) {
            output.put("error_message", content.sanitize(r.errorMessage()));
        }
        span.setAttribute(GenAiAttributes.OUTPUT_MESSAGES, content.messagesRaw("assistant", toJson(output),
                "unknown"));
        if (r.errorMessage() != null) {
            span.setAttribute(GenAiAttributes.CONTROL_ERROR_MESSAGE, content.sanitize(r.errorMessage()));
            AgentSpanRunner.markError(span, "control_evaluation_error", "control evaluation error", 500);
        }
        span.end(end);
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }
}
