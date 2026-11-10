package com.demo.bankagent.observability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.observability.api.event.AiServiceCompletedEvent;
import dev.langchain4j.observability.api.event.AiServiceErrorEvent;
import dev.langchain4j.observability.api.event.AiServiceStartedEvent;
import dev.langchain4j.observability.api.listener.AiServiceCompletedListener;
import dev.langchain4j.observability.api.listener.AiServiceErrorListener;
import dev.langchain4j.observability.api.listener.AiServiceListener;
import dev.langchain4j.observability.api.listener.AiServiceStartedListener;
import dev.langchain4j.service.tool.BeforeToolExecution;
import dev.langchain4j.service.tool.ToolExecution;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Scope;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * The only hand-written Observe adapter: it turns LangChain4j's own callbacks into OpenTelemetry GenAI spans that
 * Splunk AO classifies by {@code gen_ai.operation.name}.
 *
 * <ul>
 *   <li>{@link ChatModelListener} (onRequest / onResponse / onError) -&gt; one {@code chat <model>} span (llm) per
 *       model call, with the provider's real token usage. Registered once on the ChatModel (LlmConfig).</li>
 *   <li>AI Service listeners (started / completed / error) -&gt; one {@code invoke_agent <AiService>} span (agent)
 *       per agent invocation. Registered once per AI Service (AgentConfig).</li>
 *   <li>{@code beforeToolExecution} / {@code afterToolExecution} hooks -&gt; one {@code execute_tool <name>} span
 *       (tool) per {@code @Tool} call, started BEFORE the Java method runs. Registered once per AI Service.</li>
 * </ul>
 *
 * <p>No {@code @Tool} method, service or repository contains span code. LangChain4j calls each pair of callbacks
 * synchronously on the calling thread, so every span is current between its start and end callbacks: the agent's
 * JDBC spans and the Control spans nest under the right step. When the Java agent is not attached every callback
 * returns immediately.
 */
@Component
public class LangChain4jOtelBridge implements ChatModelListener {

    private static final String LLM_SPAN = LangChain4jOtelBridge.class.getName() + ".span";
    private static final String LLM_SCOPE = LangChain4jOtelBridge.class.getName() + ".scope";

    private final GenAiSpans spans;
    private final TraceContentPolicy content;
    private final ObjectMapper mapper;
    private final Map<Object, Open> agents = new ConcurrentHashMap<>();
    private final ThreadLocal<Deque<Open>> tools = ThreadLocal.withInitial(ArrayDeque::new);

    private record Open(Span span, Scope scope) {
        void end() {
            scope.close();
            span.end();
        }
    }

    public LangChain4jOtelBridge(GenAiSpans spans, ObjectMapper mapper) {
        this.spans = spans;
        this.content = spans.content();
        this.mapper = mapper;
    }

    // ---- LLM: ChatModelListener ---------------------------------------------------------------------------

    @Override
    public void onRequest(ChatModelRequestContext context) {
        if (!spans.active()) {
            return;
        }
        ChatRequest request = context.chatRequest();
        String model = request.parameters() == null ? null : request.parameters().modelName();
        Span span = spans.tracer().spanBuilder(model == null ? "chat" : "chat " + model)
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute(GenAiAttributes.OPERATION_NAME, GenAiAttributes.OP_CHAT)
                .setAttribute(GenAiAttributes.PROVIDER_NAME, provider(context.modelProvider()))
                .setAttribute(GenAiAttributes.REQUEST_MODEL, model == null ? "unknown" : model)
                .startSpan();
        GenAiSpans.setConversation(span, GenAiSpans.currentConversationId());
        Integer maxTokens = request.parameters() == null ? null : request.parameters().maxOutputTokens();
        if (maxTokens != null) {
            span.setAttribute(GenAiAttributes.REQUEST_MAX_TOKENS, maxTokens.longValue());
        }
        span.setAttribute(GenAiAttributes.INPUT_MESSAGES, content.chatMessages(request.messages()));
        context.attributes().put(LLM_SPAN, span);
        context.attributes().put(LLM_SCOPE, GenAiSpans.makeCurrent(span, GenAiSpans.currentConversationId()));
    }

    @Override
    public void onResponse(ChatModelResponseContext context) {
        Open open = take(context.attributes());
        if (open == null) {
            return;
        }
        ChatResponse response = context.chatResponse();
        Span span = open.span();
        if (response.modelName() != null) {
            span.setAttribute(GenAiAttributes.RESPONSE_MODEL, response.modelName());
        }
        if (response.id() != null) {
            span.setAttribute(GenAiAttributes.RESPONSE_ID, response.id());
        }
        String finish = response.finishReason() == null ? null
                : response.finishReason().name().toLowerCase(Locale.ROOT);
        if (finish != null) {
            span.setAttribute(AttributeKey.stringArrayKey(GenAiAttributes.RESPONSE_FINISH_REASONS), List.of(finish));
        }
        TokenUsage usage = response.tokenUsage();
        // Only what the provider reported; a missing count stays missing (never estimated).
        if (usage != null && usage.inputTokenCount() != null) {
            span.setAttribute(GenAiAttributes.USAGE_INPUT_TOKENS, usage.inputTokenCount().longValue());
        }
        if (usage != null && usage.outputTokenCount() != null) {
            span.setAttribute(GenAiAttributes.USAGE_OUTPUT_TOKENS, usage.outputTokenCount().longValue());
        }
        span.setAttribute(GenAiAttributes.OUTPUT_MESSAGES, content.aiOutput(response.aiMessage(), finish));
        open.end();
    }

    @Override
    public void onError(ChatModelErrorContext context) {
        Open open = take(context.attributes());
        if (open == null) {
            return;
        }
        GenAiSpans.recordFailure(open.span(), context.error());
        open.end();
    }

    private static Open take(Map<Object, Object> attributes) {
        Object span = attributes.remove(LLM_SPAN);
        Object scope = attributes.remove(LLM_SCOPE);
        return span instanceof Span s && scope instanceof Scope sc ? new Open(s, sc) : null;
    }

    // ---- Agents: AI Service listeners ---------------------------------------------------------------------

    /** Register on every AI Service: {@code AiServices.builder(..).registerListeners(bridge.aiServiceListeners())}. */
    public List<AiServiceListener<?>> aiServiceListeners() {
        return List.of((AiServiceStartedListener) this::agentStarted,
                (AiServiceCompletedListener) this::agentCompleted,
                (AiServiceErrorListener) this::agentFailed);
    }

    void agentStarted(AiServiceStartedEvent event) {
        if (!spans.active()) {
            return;
        }
        InvocationContext invocation = event.invocationContext();
        String conversationId = memoryId(invocation);
        Span span = spans.startAgent(agentName(invocation.interfaceName()), conversationId,
                event.userMessage() == null ? null : TraceContentPolicy.userText(event.userMessage()));
        agents.put(invocation.invocationId(), new Open(span, GenAiSpans.makeCurrent(span, conversationId)));
    }

    void agentCompleted(AiServiceCompletedEvent event) {
        Open open = agents.remove(event.invocationContext().invocationId());
        if (open == null) {
            return;
        }
        event.result().filter(String.class::isInstance)
                .ifPresent(result -> spans.setOutput(open.span(), (String) result));
        open.end();
    }

    void agentFailed(AiServiceErrorEvent event) {
        Open open = agents.remove(event.invocationContext().invocationId());
        if (open == null) {
            return;
        }
        GenAiSpans.recordFailure(open.span(), event.error());
        open.end();
    }

    /** "com.demo.bankagent.agent.AccountAgent" -&gt; "AccountAgent" (gen_ai.agent.name). */
    static String agentName(String interfaceName) {
        if (interfaceName == null) {
            return "AiService";
        }
        String simple = interfaceName.substring(interfaceName.lastIndexOf('.') + 1);
        return simple.substring(simple.lastIndexOf('$') + 1);
    }

    // ---- Tools: before/after tool execution hooks -----------------------------------------------------------

    /** {@code AiServices.builder(..).beforeToolExecution(bridge::toolStarted)}: runs before the @Tool method. */
    public void toolStarted(BeforeToolExecution before) {
        if (!spans.active()) {
            return;
        }
        ToolExecutionRequest request = before.request();
        String conversationId = memoryId(before.invocationContext());
        Span span = spans.startTool(request.name(), conversationId, content.toolArguments(parse(request.arguments())));
        if (request.id() != null) {
            span.setAttribute(GenAiAttributes.TOOL_CALL_ID, request.id());
        }
        tools.get().push(new Open(span, GenAiSpans.makeCurrent(span, conversationId)));
    }

    /** {@code afterToolExecution}: runs after the @Tool method (and after any Control check around it). */
    public void toolFinished(ToolExecution execution) {
        Deque<Open> stack = tools.get();
        Open open = stack.poll();
        if (stack.isEmpty()) {
            tools.remove();
        }
        if (open == null) {
            return;
        }
        open.span().setAttribute(GenAiAttributes.TOOL_CALL_RESULT, content.toolResult(execution.result()));
        if (execution.hasFailed()) {
            GenAiSpans.markError(open.span(), "tool_error", "the tool returned an error result");
        }
        open.end();
    }

    private static String memoryId(InvocationContext invocation) {
        return invocation == null || invocation.chatMemoryId() == null ? null
                : String.valueOf(invocation.chatMemoryId());
    }

    private static String provider(ModelProvider provider) {
        if (provider == null) {
            return "unknown";
        }
        return provider == ModelProvider.OPEN_AI ? GenAiAttributes.PROVIDER_OPENAI
                : provider.name().toLowerCase(Locale.ROOT);
    }

    private JsonNode parse(String json) {
        try {
            return json == null || json.isBlank() ? mapper.createObjectNode() : mapper.readTree(json);
        } catch (Exception e) {
            return mapper.createObjectNode();
        }
    }
}
