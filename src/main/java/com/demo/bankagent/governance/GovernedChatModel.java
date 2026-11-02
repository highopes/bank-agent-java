package com.demo.bankagent.governance;

import com.demo.bankagent.control.ControlExceptions.ControlDeniedException;
import com.demo.bankagent.control.ControlExceptions.ControlSteerRejectedException;
import com.demo.bankagent.control.ControlExceptions.ControlUnavailableException;
import com.demo.bankagent.control.ControlOutcome;
import com.demo.bankagent.control.ControlStage;
import com.demo.bankagent.control.ControlStep;
import com.demo.bankagent.control.ControlledStepRunner;
import com.demo.bankagent.observability.AgentSpanRunner;
import com.demo.bankagent.observability.BankTelemetry;
import com.demo.bankagent.observability.GenAiAttributes;
import com.demo.bankagent.observability.TraceContentPolicy;
import com.demo.bankagent.trace.ExecutionTrace;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Scope;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Decorator around the real (OpenAI or test) ChatModel. It is the single authoritative place for LLM spans and
 * LLM Control - one span per real model round trip, opened BEFORE the call and closed after it, so the span is the
 * child of the active agent span (unlike TraceChatModelListener.onResponse, which only fires afterwards).
 *
 * <p>Only {@link #doChat} is intercepted: LangChain4j's default chat(...) methods resolve parameters and then call
 * doChat on the same thread; the delegate's own listeners (TraceChatModelListener for the UI) still fire inside
 * delegate.chat(...). Request parameters, strict JSON schema and reasoning effort are untouched.
 *
 * <p>Control: PRE when the request ends with a new user message (the content sent to OpenAI); POST on text answers
 * (tool-call requests are checked at the tool). STEER at PRE adds the guidance as a system message; STEER at POST
 * re-asks the model at most MAX_CONTROL_STEER_RETRIES times WITHOUT tools (so a retry can never trigger a tool or a
 * write), each retry being its own LLM span and re-checked. DENY / exhausted STEER / fail-closed throw a
 * ControlException, so the blocked output never reaches the agent or the UI.
 */
public class GovernedChatModel implements ChatModel {

    static final String STEER_INSTRUCTION = "[Agent Control guidance - revise your previous answer]\n%s\n"
            + "Answer again following this guidance. Do not call tools. Do not invent amounts, names, dates, "
            + "account numbers or other facts.";

    private final ChatModel delegate;
    private final String stepName;
    private final boolean controlled;
    private final BankTelemetry telemetry;
    private final TraceContentPolicy content;
    private final ControlledStepRunner runner;

    public GovernedChatModel(ChatModel delegate, String stepName, boolean controlled, BankTelemetry telemetry,
                             TraceContentPolicy content, ControlledStepRunner runner) {
        this.delegate = delegate;
        this.stepName = stepName;
        this.controlled = controlled;
        this.telemetry = telemetry;
        this.content = content;
        this.runner = runner;
    }

    public ChatModel delegate() {
        return delegate;
    }

    public String stepName() {
        return stepName;
    }

    @Override
    public ChatRequestParameters defaultRequestParameters() {
        return delegate.defaultRequestParameters();
    }

    @Override
    public List<ChatModelListener> listeners() {
        // The delegate fires its own listeners inside delegate.chat(...); avoid firing them twice.
        return List.of();
    }

    @Override
    public ModelProvider provider() {
        return delegate.provider();
    }

    @Override
    public Set<Capability> supportedCapabilities() {
        return delegate.supportedCapabilities();
    }

    @Override
    public ChatResponse doChat(ChatRequest request) {
        String conversationId = ExecutionTrace.current().map(ExecutionTrace::conversationId).orElse(null);
        Attempt attempt = call(request, conversationId, 0, controlled && endsWithUserMessage(request));
        int retries = 0;
        while (attempt.steer() != null) {
            if (retries >= runner.maxSteerRetries()) {
                throw new ControlSteerRejectedException(ControlStage.POST, stepName, attempt.steer().decision());
            }
            retries++;
            attempt = call(steerRetry(attempt.request(), attempt.response(), attempt.steer().steeringContext()),
                    conversationId, retries, false);
        }
        return attempt.response();
    }

    private record Attempt(ChatRequest request, ChatResponse response, ControlOutcome steer) {
    }

    private Attempt call(ChatRequest request, String conversationId, int steerAttempt, boolean checkPre) {
        String model = modelName(request);
        Span span = telemetry.tracer().spanBuilder(model == null ? "chat" : "chat " + model)
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute(GenAiAttributes.OPERATION_NAME, GenAiAttributes.OP_CHAT)
                .setAttribute(GenAiAttributes.PROVIDER_NAME, GenAiAttributes.PROVIDER_OPENAI)
                .setAttribute(GenAiAttributes.REQUEST_MODEL, model == null ? "unknown" : model)
                .setAttribute(GenAiAttributes.CONVERSATION_ID, conversationId == null ? "" : conversationId)
                .setAttribute("bank.llm.step", stepName)
                .startSpan();
        if (steerAttempt > 0) {
            span.setAttribute(GenAiAttributes.BANK_STEER_ATTEMPT, steerAttempt);
        }
        try (Scope ignored = span.makeCurrent()) {
            ChatRequest effective = request;
            String userText = lastUserText(request);
            if (checkPre) {
                ControlOutcome pre = runner.check(ControlStage.PRE,
                        ControlStep.llm(stepName, userText, null, conversationId));
                switch (pre.verdict()) {
                    case DENY -> throw new ControlDeniedException(ControlStage.PRE, stepName, pre.decision());
                    case BLOCKED_UNAVAILABLE -> throw new ControlUnavailableException(ControlStage.PRE, stepName,
                            pre.decision());
                    case STEER -> effective = withGuidance(request, pre.steeringContext());
                    default -> {
                    }
                }
            }
            span.setAttribute(GenAiAttributes.INPUT_MESSAGES, content.chatMessages(effective.messages()));
            Integer maxTokens = effective.parameters() == null ? null : effective.parameters().maxOutputTokens();
            if (maxTokens != null) {
                span.setAttribute(GenAiAttributes.REQUEST_MAX_TOKENS, maxTokens.longValue());
            }

            ChatResponse response = delegate.chat(effective);
            record(span, response);

            ControlOutcome steer = null;
            AiMessage ai = response.aiMessage();
            if (controlled && ai != null && !ai.hasToolExecutionRequests() && ai.text() != null) {
                ControlOutcome post = runner.check(ControlStage.POST,
                        ControlStep.llm(stepName, userText, ai.text(), conversationId));
                switch (post.verdict()) {
                    case DENY -> throw new ControlDeniedException(ControlStage.POST, stepName, post.decision());
                    case BLOCKED_UNAVAILABLE -> throw new ControlUnavailableException(ControlStage.POST, stepName,
                            post.decision());
                    case STEER -> steer = post;
                    default -> {
                    }
                }
            }
            return new Attempt(effective, response, steer);
        } catch (RuntimeException e) {
            AgentSpanRunner.recordFailure(span, e);
            throw e;
        } finally {
            span.end();
        }
    }

    private void record(Span span, ChatResponse response) {
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
        if (usage != null) {
            // Real counts reported by the provider (ChatResponse.tokenUsage()), never estimated.
            if (usage.inputTokenCount() != null) {
                span.setAttribute(GenAiAttributes.USAGE_INPUT_TOKENS, usage.inputTokenCount().longValue());
            }
            if (usage.outputTokenCount() != null) {
                span.setAttribute(GenAiAttributes.USAGE_OUTPUT_TOKENS, usage.outputTokenCount().longValue());
            }
        }
        span.setAttribute(GenAiAttributes.OUTPUT_MESSAGES, content.aiOutput(response.aiMessage(), finish));
    }

    private String modelName(ChatRequest request) {
        if (request.parameters() != null && request.parameters().modelName() != null) {
            return request.parameters().modelName();
        }
        ChatRequestParameters defaults = delegate.defaultRequestParameters();
        return defaults == null ? null : defaults.modelName();
    }

    static boolean endsWithUserMessage(ChatRequest request) {
        List<ChatMessage> messages = request.messages();
        return !messages.isEmpty() && messages.get(messages.size() - 1) instanceof UserMessage;
    }

    static String lastUserText(ChatRequest request) {
        List<ChatMessage> messages = request.messages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage user) {
                return TraceContentPolicy.userText(user);
            }
        }
        return "";
    }

    static ChatRequest withGuidance(ChatRequest request, String guidance) {
        List<ChatMessage> messages = new ArrayList<>(request.messages());
        messages.add(messages.size() - 1, SystemMessage.from("[Agent Control guidance] " + guidance));
        return request.toBuilder().messages(messages).build();
    }

    /** Text-only retry: the rejected answer, then the guidance; no tool specifications are sent. */
    static ChatRequest steerRetry(ChatRequest request, ChatResponse rejected, String guidance) {
        List<ChatMessage> messages = new ArrayList<>(request.messages());
        messages.add(rejected.aiMessage());
        messages.add(SystemMessage.from(String.format(STEER_INSTRUCTION, guidance)));
        ChatRequest.Builder builder = ChatRequest.builder().messages(messages);
        if (request.responseFormat() != null) {
            builder.responseFormat(request.responseFormat());
        }
        return builder.build();
    }
}
