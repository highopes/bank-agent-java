package com.demo.bankagent.observability;

import com.demo.bankagent.llm.SecretMasker;
import com.demo.bankagent.service.BankingException;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Creates "invoke_agent &lt;name&gt;" spans around real agent executions (task book 4.4): the span is started BEFORE
 * the agent runs, made current so that LLM/tool spans become its children, and always ended in finally.
 */
@Component
public class AgentSpanRunner {

    private final BankTelemetry telemetry;
    private final TraceContentPolicy content;

    public AgentSpanRunner(BankTelemetry telemetry, TraceContentPolicy content) {
        this.telemetry = telemetry;
        this.content = content;
    }

    /** Starts (but does not activate) an agent span; the caller makes it current and ends it. */
    public Span startAgent(String agentName, String conversationId, String inputText) {
        return telemetry.tracer().spanBuilder("invoke_agent " + agentName)
                .setSpanKind(SpanKind.INTERNAL)
                .setAttribute(GenAiAttributes.OPERATION_NAME, GenAiAttributes.OP_INVOKE_AGENT)
                .setAttribute(GenAiAttributes.AGENT_NAME, agentName)
                .setAttribute(GenAiAttributes.PROVIDER_NAME, GenAiAttributes.PROVIDER_OPENAI)
                .setAttribute(GenAiAttributes.CONVERSATION_ID, conversationId == null ? "" : conversationId)
                .setAttribute(GenAiAttributes.INPUT_MESSAGES, content.messages("user", inputText, null))
                .setAttribute(GenAiAttributes.BANK_CONTENT_CAPTURED, content.captures())
                .startSpan();
    }

    /** Runs {@code body} inside an agent span and records its text output or failure. */
    public <T> T runAgent(String agentName, String conversationId, String inputText, Function<T, String> output,
                          Supplier<T> body) {
        Span span = startAgent(agentName, conversationId, inputText);
        try (Scope ignored = span.makeCurrent()) {
            T result = body.get();
            setOutput(span, output.apply(result));
            return result;
        } catch (RuntimeException e) {
            recordFailure(span, e);
            throw e;
        } finally {
            span.end();
        }
    }

    public void setOutput(Span span, String text) {
        if (text != null) {
            span.setAttribute(GenAiAttributes.OUTPUT_MESSAGES, content.messages("assistant", text, "stop"));
        }
    }

    /**
     * Marks a span as failed with error.type and a safe description. Deliberately does NOT call recordException:
     * that would export the stack trace and possibly raw input (task book 5.7).
     */
    public static void recordFailure(Span span, Throwable error) {
        String type = error instanceof BankingException be ? be.getCode().name() : error.getClass().getSimpleName();
        String message = error instanceof BankingException ? SecretMasker.mask(error.getMessage()) : type;
        if (message != null && message.length() > 300) {
            message = message.substring(0, 300) + "...";
        }
        int status = error instanceof BankingException be ? be.getCode().status().value() : 500;
        span.addEvent("exception", Attributes.of(AttributeKey.stringKey("exception.type"), type,
                AttributeKey.stringKey("exception.message"), message == null ? type : message));
        markError(span, type, message == null ? type : message, status);
    }

    /** Failed step: OTel status ERROR + error.type, and the integer status that AO shows (splunk_ao.status_code). */
    public static void markError(Span span, String errorType, String description, int aoStatusCode) {
        span.setAttribute(GenAiAttributes.ERROR_TYPE, errorType);
        span.setAttribute(GenAiAttributes.AO_STATUS_CODE, aoStatusCode);
        span.setStatus(StatusCode.ERROR, description);
    }

    /** Trace ID of the active span, or null when Observe is off / no span is active. */
    public static String currentTraceId() {
        SpanContext context = Span.current().getSpanContext();
        return context.isValid() ? context.getTraceId() : null;
    }
}
