package com.demo.bankagent.conversation;

import com.demo.bankagent.agent.SupervisorAgent;
import com.demo.bankagent.config.BankAgentProperties;
import com.demo.bankagent.control.ControlDecision;
import com.demo.bankagent.control.ControlExceptions.ControlDeniedException;
import com.demo.bankagent.control.ControlExceptions.ControlSteerRejectedException;
import com.demo.bankagent.control.ControlExceptions.ControlUnavailableException;
import com.demo.bankagent.control.ControlOutcome;
import com.demo.bankagent.control.ControlStage;
import com.demo.bankagent.control.ControlStep;
import com.demo.bankagent.control.ControlledStepRunner;
import com.demo.bankagent.control.StepCatalog;
import com.demo.bankagent.llm.LlmErrorTranslator;
import com.demo.bankagent.observability.AoIds;
import com.demo.bankagent.observability.GenAiAttributes;
import com.demo.bankagent.observability.GenAiSpans;
import com.demo.bankagent.service.BankingException;
import com.demo.bankagent.service.ErrorCode;
import com.demo.bankagent.trace.AgentStepTrace;
import com.demo.bankagent.trace.ControlDecisionTrace;
import com.demo.bankagent.trace.DraftEventTrace;
import com.demo.bankagent.trace.ExecutionTrace;
import com.demo.bankagent.trace.LlmCallTrace;
import com.demo.bankagent.trace.ToolCallTrace;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Entry point for one chat turn: resolves or creates the conversation, serializes turns of the same
 * conversation, runs the Supervisor and returns the answer together with the real execution trace.
 *
 * <p>Observe: one of the two hand-written spans of the application. Each turn is wrapped in
 * "invoke_agent BankingAssistant" (gen_ai.conversation.id, so AO groups the turns of a conversation into one
 * session). It is needed because a turn - Supervisor routing plus several LangChain4j AI Services - is not a single
 * framework call, so no LangChain4j event marks its start and end. Its parent is the HTTP request span of the
 * OpenTelemetry Java agent; the router, agent, LLM and tool spans below it come from LangChain4j listeners.
 *
 * <p>Control: Agent Control step "banking-assistant" checks the user's message before the Supervisor runs (PRE)
 * and the final answer before it is returned (POST). A blocked turn returns a clear, safe answer; protected content
 * is never returned.
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private static final long LOCK_WAIT_SECONDS = 5;
    static final String ROOT_AGENT = "BankingAssistant";
    static final String WITHHELD_USER_MESSAGE = "[A user message was blocked by Agent Control and is not shown.]";
    private static final String REWRITE_PROMPT = "You rewrite the final answer of a demo banking assistant so that it "
            + "follows a policy guidance. Keep every fact, number, amount, name, date and ID exactly as in the original "
            + "answer and do not add new facts. Keep the original language. Output only the rewritten answer.";

    private final ConversationService conversationService;
    private final SupervisorAgent supervisor;
    private final BankAgentProperties properties;
    private final ControlledStepRunner control;
    private final GenAiSpans spans;
    private final ChatModel rewriter;

    public ChatService(ConversationService conversationService, SupervisorAgent supervisor,
                       BankAgentProperties properties, ControlledStepRunner control, GenAiSpans spans,
                       ChatModel chatModel) {
        this.conversationService = conversationService;
        this.supervisor = supervisor;
        this.properties = properties;
        this.control = control;
        this.spans = spans;
        this.rewriter = chatModel;
    }

    public record ChatTurnResult(String conversationId, boolean newConversation, String messageId, String answer,
                                 String intent, List<String> selectedAgents, List<String> executedTools,
                                 List<AgentStepTrace> agentSteps, List<ToolCallTrace> toolCalls,
                                 List<LlmCallTrace> llmCalls, Object pendingDraft, List<DraftEventTrace> draftEvents,
                                 long durationMs, String model, List<ControlDecisionTrace> controlDecisions,
                                 String traceId, String aoTraceId) {
    }

    /** Outcome of the governed part of a turn. */
    private record Turn(String answer, String intent, List<String> agents, boolean inputWithheld) {
    }

    public ChatTurnResult chat(String conversationId, String rawMessage) {
        String message = rawMessage == null ? "" : rawMessage.strip();
        if (message.isEmpty()) {
            throw new BankingException(ErrorCode.VALIDATION_ERROR, "Message must not be empty.");
        }
        int max = properties.agent().maxMessageLength();
        if (message.length() > max) {
            throw new BankingException(ErrorCode.VALIDATION_ERROR, "Message is too long (max " + max + " characters).");
        }
        boolean created = false;
        Conversation conversation = conversationService.find(conversationId).orElse(null);
        if (conversation == null) {
            conversation = conversationService.create();
            created = true;
        }
        if (!tryLock(conversation)) {
            throw new BankingException(ErrorCode.CONVERSATION_BUSY,
                    "The previous message in this conversation is still being processed. Please wait.");
        }
        long start = System.nanoTime();
        ExecutionTrace trace = ExecutionTrace.begin(conversation.getId());
        trace.setUserMessage(message);
        Span root = spans.startAgent(ROOT_AGENT, conversation.getId(), message);
        try (Scope ignored = GenAiSpans.makeCurrent(root, conversation.getId())) {
            trace.setTraceId(GenAiSpans.traceId(root));
            List<Conversation.Turn> history = conversation.recentTurns(properties.agent().routerHistoryTurns());
            Turn turn = governedTurn(conversation.getId(), message, history);
            conversationService.recordUserMessage(conversation, turn.inputWithheld() ? WITHHELD_USER_MESSAGE : message);
            conversationService.recordAssistantMessage(conversation, turn.answer());
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            List<ToolCallTrace> toolCalls = trace.toolCalls();
            root.setAttribute(GenAiAttributes.BANK_INTENT, turn.intent());
            root.setAttribute(AttributeKey.stringArrayKey(GenAiAttributes.BANK_SELECTED_AGENTS), turn.agents());
            spans.setOutput(root, turn.answer());
            log.info("Chat turn conversation={} intent={} agents={} tools={} llmCalls={} controls={} durationMs={}"
                            + " traceId={}", conversation.getId(), turn.intent(), turn.agents(),
                    toolCalls.stream().map(ToolCallTrace::tool).toList(), trace.llmCalls().size(),
                    trace.controlDecisions().size(), durationMs, trace.traceId() == null ? "-" : trace.traceId());
            return new ChatTurnResult(conversation.getId(), created, UUID.randomUUID().toString(), turn.answer(),
                    turn.intent(), turn.agents(), toolCalls.stream().map(ToolCallTrace::tool).toList(),
                    trace.agentSteps(), toolCalls, trace.llmCalls(), trace.pendingDraft(), trace.draftEvents(),
                    durationMs, properties.openai().model(), trace.controlDecisions(), trace.traceId(),
                    AoIds.aoTraceUuid(trace.traceId()));
        } catch (RuntimeException e) {
            GenAiSpans.recordFailure(root, e);
            throw e;
        } finally {
            root.end();
            ExecutionTrace.end();
            conversation.lock().unlock();
        }
    }

    private Turn governedTurn(String conversationId, String message, List<Conversation.Turn> history) {
        ControlOutcome pre = control.check(ControlStage.PRE,
                ControlStep.llm(StepCatalog.BANKING_ASSISTANT, message, null, conversationId));
        switch (pre.verdict()) {
            case DENY:
                return new Turn(blockedAnswer("您的请求已被安全策略拦截，未交给助理处理", pre.decision()),
                        "BLOCKED", List.of(), true);
            case STEER:
                return new Turn("您的请求需要调整后才能处理：" + pre.steeringContext()
                        + "（来自 Agent Control" + nameOf(pre.decision()) + "）。请修改后重新发送。",
                        "STEERED", List.of(), true);
            case BLOCKED_UNAVAILABLE:
                return new Turn("安全检查服务暂时不可用，本次请求未被处理（fail-closed）。请稍后重试。",
                        "CONTROL_UNAVAILABLE", List.of(), false);
            default:
                break;
        }
        SupervisorAgent.SupervisorResult result;
        try {
            result = supervisor.handle(conversationId, message, history);
        } catch (ControlDeniedException e) {
            return new Turn(blockedAnswer("该操作已被安全策略拦截（" + e.stage().wire() + " " + e.stepName() + "）",
                    e.decision()), "BLOCKED", List.of(), false);
        } catch (ControlSteerRejectedException e) {
            return new Turn("安全策略要求调整后才能继续：" + safeSteer(e.decision())
                    + "。已停止自动重试，请修改您的请求后重新发送。", "STEERED", List.of(), false);
        } catch (ControlUnavailableException e) {
            return new Turn("安全检查服务暂时不可用，相关步骤未执行（fail-closed）。请稍后重试。",
                    "CONTROL_UNAVAILABLE", List.of(), false);
        }
        FinalAnswer finalAnswer = checkFinalAnswer(conversationId, message, result.answer());
        if (finalAnswer.text() == null) {
            String prefix = finalAnswer.unavailable() ? "安全检查服务暂时不可用，回答未展示（fail-closed）"
                    : "回答内容已被安全策略拦截，未向您展示";
            return new Turn(blockedAnswer(prefix, finalAnswer.decision()), "BLOCKED", result.selectedAgents(),
                    false);
        }
        return new Turn(finalAnswer.text(), result.intent(), result.selectedAgents(), false);
    }

    /** Final answer after the POST check; text is null when it must not be shown. */
    private record FinalAnswer(String text, ControlDecision decision, boolean unavailable) {
    }

    /**
     * POST check of the final answer. STEER triggers at most MAX_CONTROL_STEER_RETRIES rewrites of the answer text
     * (a text-only model call, never re-running agents or tools); each rewrite is checked again. Returns null when
     * the answer must not be shown (DENY, exhausted STEER or fail-closed).
     */
    private FinalAnswer checkFinalAnswer(String conversationId, String message, String answer) {
        String current = answer;
        for (int attempt = 0; ; attempt++) {
            ControlOutcome post = control.check(ControlStage.POST,
                    ControlStep.llm(StepCatalog.BANKING_ASSISTANT, message, current, conversationId));
            switch (post.verdict()) {
                case PROCEED, PROCEED_UNCHECKED:
                    return new FinalAnswer(current, post.decision(), false);
                case STEER:
                    if (attempt >= control.maxSteerRetries()) {
                        return new FinalAnswer(null, post.decision(), false);
                    }
                    current = rewrite(current, post.steeringContext());
                    break;
                case BLOCKED_UNAVAILABLE:
                    return new FinalAnswer(null, post.decision(), true);
                default:
                    return new FinalAnswer(null, post.decision(), false);
            }
        }
    }

    private String rewrite(String answer, String guidance) {
        ChatRequest request = ChatRequest.builder().messages(SystemMessage.from(REWRITE_PROMPT),
                UserMessage.from("Policy guidance:\n" + guidance + "\n\nOriginal answer:\n" + answer)).build();
        try {
            ChatResponse response = rewriter.chat(request);
            String text = response.aiMessage() == null ? null : response.aiMessage().text();
            return text == null || text.isBlank() ? answer : text.strip();
        } catch (RuntimeException e) {
            throw LlmErrorTranslator.translate(e);
        }
    }

    private static String blockedAnswer(String prefix, ControlDecision decision) {
        StringBuilder sb = new StringBuilder(prefix);
        if (decision != null && decision.controlName() != null) {
            sb.append("（Agent Control 控制项：").append(decision.controlName());
            if (decision.reason() != null) {
                sb.append("；原因：").append(decision.reason());
            }
            sb.append("）");
        }
        return sb.append("。如有疑问请换一种方式提问。").toString();
    }

    private static String nameOf(ControlDecision decision) {
        return decision.controlName() == null ? "" : " 控制项 " + decision.controlName();
    }

    private static String safeSteer(ControlDecision decision) {
        String text = decision.steeringContext() == null ? decision.reason() : decision.steeringContext();
        return text == null ? "请调整请求" : text;
    }

    private static boolean tryLock(Conversation conversation) {
        try {
            return conversation.lock().tryLock(LOCK_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
