package com.demo.bankagent.control;

import com.demo.bankagent.observability.AoBackendProfile;
import com.demo.bankagent.observability.AoSettings;
import com.demo.bankagent.observability.GenAiAttributes;
import com.demo.bankagent.observability.GenAiSpans;
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
 * Reports what Agent Control actually evaluated (UI execution trace, step span metadata, Control spans). It is a
 * separate component from the gateway because the server's /evaluation endpoint only returns a decision and records
 * no Control event by itself (agent-control evaluation_events.py rebuilds events client-side).
 *
 * <p>Event channel (docs/galileo/PROTOCOL-VERIFICATION.md): the official Splunk AO Python SDK turns every
 * ControlExecutionEvent into a "control span" (splunk_ao handlers/agent_control/bridge.py -&gt; add_control_span)
 * exported via OTLP with galileo.span.kind=control and agent_control.* attributes
 * (converter/attribute_mapping.set_control_attributes). This class builds the same span with the OpenTelemetry
 * API, as a child of the evaluated step's span (the current span), for every match / non-match / error returned
 * by the server. It is exported by the same OpenTelemetry Java agent as every other span - there is no second
 * exporter or HTTP channel. Without Observe (no agent) nothing can be exported; that state is reported as
 * EVENT_INGEST_UNAVAILABLE and nothing is faked. The spans are evidence only: they never change a decision.
 */
@Component
public class ControlEventReporter {

    private static final Logger log = LoggerFactory.getLogger(ControlEventReporter.class);
    public static final String EVENT_INGEST_UNAVAILABLE = "EVENT_INGEST_UNAVAILABLE";

    private final GenAiSpans spans;
    private final AoSettings settings;
    private final TraceContentPolicy content;
    private final ObjectMapper mapper;
    private final AtomicBoolean warnedNoChannel = new AtomicBoolean();

    public ControlEventReporter(GenAiSpans spans, AoSettings settings, TraceContentPolicy content,
                                ObjectMapper mapper) {
        this.spans = spans;
        this.settings = settings;
        this.content = content;
        this.mapper = mapper;
    }

    /** Status text for /api/status and the docs. */
    public String eventChannel() {
        if (!spans.active()) {
            return EVENT_INGEST_UNAVAILABLE + " (Observe off - no OpenTelemetry Java agent: control spans cannot be "
                    + "exported)";
        }
        return settings.profile() == AoBackendProfile.O11Y
                ? "OTLP control spans via the OpenTelemetry Java agent (verified for profile o11y)"
                : "OTLP control spans via the OpenTelemetry Java agent (same contract; not verified online for "
                + "profile galileo)";
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
        if (!spans.active()) {
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
        Span span = spans.tracer().spanBuilder(r.controlName())
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
            GenAiSpans.markError(span, "control_evaluation_error", "control evaluation error");
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
