package com.demo.bankagent.governance;

import com.demo.bankagent.control.ControlExceptions.ControlDeniedException;
import com.demo.bankagent.control.ControlExceptions.ControlSteerRejectedException;
import com.demo.bankagent.control.ControlExceptions.ControlUnavailableException;
import com.demo.bankagent.control.ControlOutcome;
import com.demo.bankagent.control.ControlStage;
import com.demo.bankagent.control.ControlStep;
import com.demo.bankagent.control.ControlledStepRunner;
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
import io.opentelemetry.api.trace.Span;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Agent Control enforcement around one model step (Control only; created by {@link StepGovernance} when
 * CONTROL_MODE=remote-api). It creates no span.
 *
 * <p>Observe stays with the model's own listeners: {@link #listeners()} returns the wrapped model's listeners
 * (the LangChain4j OTel bridge and the UI trace), so LangChain4j fires them once around the WHOLE governed step and
 * the PRE/POST checks - and their Control spans - happen inside that step's {@code chat} span. The real model is
 * then called through {@code doChat}, which does not fire the listeners a second time.
 *
 * <p>PRE when the request ends with a new user message (the content sent to OpenAI); POST on text answers
 * (tool-call requests are checked at the tool). STEER at PRE adds the guidance as a system message; STEER at POST
 * re-asks the model at most MAX_CONTROL_STEER_RETRIES times WITHOUT tools (a retry can never trigger a tool or a
 * write), and every retry is checked again. The returned token usage is the sum of all round trips of the step.
 * DENY / exhausted STEER / fail-closed throw a ControlException, so blocked output never reaches the agent or UI.
 */
public class GovernedChatModel implements ChatModel {

    static final String STEER_INSTRUCTION = "[Agent Control guidance - revise your previous answer]\n%s\n"
            + "Answer again following this guidance. Do not call tools. Do not invent amounts, names, dates, "
            + "account numbers or other facts.";

    private final ChatModel delegate;
    private final String stepName;
    private final ControlledStepRunner runner;

    public GovernedChatModel(ChatModel delegate, String stepName, ControlledStepRunner runner) {
        this.delegate = delegate instanceof GovernedChatModel governed ? governed.delegate() : delegate;
        this.stepName = stepName;
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
        return delegate.listeners();
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
        String userText = lastUserText(request);
        ChatRequest effective = request;
        if (endsWithUserMessage(request)) {
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

        ChatResponse response = delegate.doChat(effective);
        TokenUsage usage = response.tokenUsage();
        int retries = 0;
        while (true) {
            AiMessage ai = response.aiMessage();
            if (ai == null || ai.hasToolExecutionRequests() || ai.text() == null) {
                return withUsage(response, usage);
            }
            ControlOutcome post = runner.check(ControlStage.POST,
                    ControlStep.llm(stepName, userText, ai.text(), conversationId));
            switch (post.verdict()) {
                case DENY -> throw new ControlDeniedException(ControlStage.POST, stepName, post.decision());
                case BLOCKED_UNAVAILABLE -> throw new ControlUnavailableException(ControlStage.POST, stepName,
                        post.decision());
                case STEER -> {
                    if (retries >= runner.maxSteerRetries()) {
                        throw new ControlSteerRejectedException(ControlStage.POST, stepName, post.decision());
                    }
                    retries++;
                    Span.current().setAttribute(GenAiAttributes.BANK_CONTROL_STEER_RETRIES, retries);
                    response = delegate.doChat(steerRetry(effective, response, post.steeringContext()));
                    usage = TokenUsage.sum(usage, response.tokenUsage());
                }
                default -> {
                    return withUsage(response, usage);
                }
            }
        }
    }

    private static ChatResponse withUsage(ChatResponse response, TokenUsage usage) {
        if (usage == response.tokenUsage()) {
            return response;
        }
        return response.toBuilder().metadata(response.metadata().toBuilder().tokenUsage(usage).build()).build();
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

    /**
     * Text-only retry: the rejected answer, then the guidance. The parameters are the model defaults plus the
     * original response format - no tool specifications are sent.
     */
    ChatRequest steerRetry(ChatRequest request, ChatResponse rejected, String guidance) {
        List<ChatMessage> messages = new ArrayList<>(request.messages());
        messages.add(rejected.aiMessage());
        messages.add(SystemMessage.from(String.format(STEER_INSTRUCTION, guidance)));
        var retry = ChatRequestParameters.builder();
        if (request.responseFormat() != null) {
            retry.responseFormat(request.responseFormat());
        }
        return ChatRequest.builder().messages(messages)
                .parameters(delegate.defaultRequestParameters().overrideWith(retry.build())).build();
    }
}
