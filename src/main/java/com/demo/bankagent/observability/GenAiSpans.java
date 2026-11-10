package com.demo.bankagent.observability;

import com.demo.bankagent.llm.SecretMasker;
import com.demo.bankagent.service.BankingException;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import io.opentelemetry.context.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The application's single access point to OpenTelemetry. The {@link OpenTelemetry} instance is the one the
 * official OpenTelemetry Java agent installs ({@link GlobalOpenTelemetry}); without the agent it is the no-op
 * implementation, so every call below does nothing and nothing leaves the process. There is no SDK, exporter or
 * sampler in the application itself.
 *
 * <p>Users: {@link LangChain4jOtelBridge} (agent / LLM / tool spans from LangChain4j callbacks), the two
 * hand-written business spans (chat turn root in ChatService, UI payment confirmation in PaymentController) and
 * the Control span reporter.
 */
@Component
public class GenAiSpans {

    private static final Logger log = LoggerFactory.getLogger(GenAiSpans.class);
    public static final String INSTRUMENTATION_SCOPE = "com.demo.bankagent";
    /** Conversation (AO session) ID handed to child spans through the OTel Context; never sent over the wire. */
    private static final ContextKey<String> CONVERSATION_ID = ContextKey.named("bank-agent.gen_ai.conversation.id");

    private final Tracer tracer;
    private final TraceContentPolicy content;
    private final boolean active;

    @Autowired
    public GenAiSpans(OpenTelemetry openTelemetry, TraceContentPolicy content, AoSettings settings) {
        this(openTelemetry, content);
        if (active) {
            log.info("Observe: OpenTelemetry Java agent detected - GenAI spans are exported by the agent.");
            if (!settings.observabilityEnabled()) {
                log.warn("Observe: the OpenTelemetry Java agent is attached but OBSERVABILITY_ENABLED=false; spans "
                        + "are still exported by the agent. Start with scripts/run.sh (without --observe) to disable.");
            }
        } else if (settings.observabilityEnabled()) {
            log.warn("Observe: OBSERVABILITY_ENABLED=true but the OpenTelemetry Java agent is NOT attached, so no "
                    + "spans are recorded. Start the application with scripts/run.sh --observe.");
        } else {
            log.info("Observe: off (no OpenTelemetry Java agent) - no spans are recorded or exported.");
        }
    }

    public GenAiSpans(OpenTelemetry openTelemetry, TraceContentPolicy content) {
        this.tracer = openTelemetry.getTracer(INSTRUMENTATION_SCOPE);
        this.content = content;
        // A real SDK (the agent or a test SDK) returns a valid span context even for unsampled spans; the no-op
        // API never does. The probe span is not ended, so it is never exported.
        this.active = tracer.spanBuilder("observe-probe").startSpan().getSpanContext().isValid();
    }

    /** True when an OpenTelemetry SDK is installed (normally: the Java agent is attached). */
    public boolean active() {
        return active;
    }

    public Tracer tracer() {
        return tracer;
    }

    public TraceContentPolicy content() {
        return content;
    }

    /** Starts (does not activate) an "invoke_agent &lt;name&gt;" span. */
    public Span startAgent(String agentName, String conversationId, String inputText) {
        Span span = tracer.spanBuilder("invoke_agent " + agentName)
                .setSpanKind(SpanKind.INTERNAL)
                .setAttribute(GenAiAttributes.OPERATION_NAME, GenAiAttributes.OP_INVOKE_AGENT)
                .setAttribute(GenAiAttributes.AGENT_NAME, agentName)
                .setAttribute(GenAiAttributes.PROVIDER_NAME, GenAiAttributes.PROVIDER_OPENAI)
                .setAttribute(GenAiAttributes.INPUT_MESSAGES, content.messages("user", inputText, null))
                .startSpan();
        setConversation(span, conversationId);
        return span;
    }

    /** Starts (does not activate) an "execute_tool &lt;name&gt;" span for a step that LangChain4j does not run. */
    public Span startTool(String toolName, String conversationId, String argumentsJson) {
        Span span = tracer.spanBuilder("execute_tool " + toolName)
                .setSpanKind(SpanKind.INTERNAL)
                .setAttribute(GenAiAttributes.OPERATION_NAME, GenAiAttributes.OP_EXECUTE_TOOL)
                .setAttribute(GenAiAttributes.TOOL_NAME, toolName)
                .setAttribute(GenAiAttributes.TOOL_CALL_ARGUMENTS, argumentsJson)
                .startSpan();
        setConversation(span, conversationId);
        return span;
    }

    /**
     * Makes {@code span} current (with the conversation ID for its child spans). Closing the returned scope also
     * repairs the thread's context if an inner scope was left open by an abnormal exit (e.g. an Error thrown
     * between two framework callbacks), so a leaked span can never become the parent of a later request.
     */
    public static Scope makeCurrent(Span span, String conversationId) {
        Context parent = Context.current();
        Context context = parent.with(span);
        if (conversationId != null && !conversationId.isBlank()) {
            context = context.with(CONVERSATION_ID, conversationId);
        }
        Scope scope = context.makeCurrent();
        SpanContext expected = Span.fromContext(parent).getSpanContext();
        return () -> {
            scope.close();
            if (!Span.current().getSpanContext().equals(expected)) {
                log.warn("OpenTelemetry context was not restored after span '{}' (an inner scope leaked); repairing.",
                        span.getSpanContext().getSpanId());
                @SuppressWarnings({"unused", "resource"})
                Scope repaired = parent.makeCurrent();
            }
        };
    }

    /** Conversation ID of the current agent/turn span, if any. */
    public static String currentConversationId() {
        return Context.current().get(CONVERSATION_ID);
    }

    public static void setConversation(Span span, String conversationId) {
        if (conversationId != null && !conversationId.isBlank()) {
            span.setAttribute(GenAiAttributes.CONVERSATION_ID, conversationId);
        }
    }

    public void setOutput(Span span, String text) {
        if (text != null) {
            span.setAttribute(GenAiAttributes.OUTPUT_MESSAGES, content.messages("assistant", text, "stop"));
        }
    }

    /**
     * Marks a span as failed: OTel status ERROR, error.type and an "exception" event with type and a safe message.
     * Deliberately does NOT call recordException, which would export the stack trace and possibly raw input.
     */
    public static void recordFailure(Span span, Throwable error) {
        String type = error instanceof BankingException be ? be.getCode().name() : error.getClass().getSimpleName();
        String message = error instanceof BankingException ? SecretMasker.mask(error.getMessage()) : type;
        if (message == null) {
            message = type;
        } else if (message.length() > 300) {
            message = message.substring(0, 300) + "...";
        }
        span.addEvent("exception", Attributes.of(AttributeKey.stringKey("exception.type"), type,
                AttributeKey.stringKey("exception.message"), message));
        markError(span, type, message);
    }

    public static void markError(Span span, String errorType, String description) {
        span.setAttribute(GenAiAttributes.ERROR_TYPE, errorType);
        span.setStatus(StatusCode.ERROR, description);
    }

    /** Trace ID of a span, or null when Observe is off. */
    public static String traceId(Span span) {
        SpanContext context = span.getSpanContext();
        return context.isValid() ? context.getTraceId() : null;
    }
}
