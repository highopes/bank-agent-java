package com.demo.bankagent.conversation;

import com.demo.bankagent.agent.SupervisorAgent;
import com.demo.bankagent.config.BankAgentProperties;
import com.demo.bankagent.service.BankingException;
import com.demo.bankagent.service.ErrorCode;
import com.demo.bankagent.trace.AgentStepTrace;
import com.demo.bankagent.trace.DraftEventTrace;
import com.demo.bankagent.trace.ExecutionTrace;
import com.demo.bankagent.trace.LlmCallTrace;
import com.demo.bankagent.trace.ToolCallTrace;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Entry point for one chat turn: resolves or creates the conversation, serializes turns of the same
 * conversation, runs the Supervisor and returns the answer together with the real execution trace.
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private static final long LOCK_WAIT_SECONDS = 5;

    private final ConversationService conversationService;
    private final SupervisorAgent supervisor;
    private final BankAgentProperties properties;

    public ChatService(ConversationService conversationService, SupervisorAgent supervisor,
                       BankAgentProperties properties) {
        this.conversationService = conversationService;
        this.supervisor = supervisor;
        this.properties = properties;
    }

    public record ChatTurnResult(String conversationId, boolean newConversation, String messageId, String answer,
                                 String intent, List<String> selectedAgents, List<String> executedTools,
                                 List<AgentStepTrace> agentSteps, List<ToolCallTrace> toolCalls,
                                 List<LlmCallTrace> llmCalls, Object pendingDraft, List<DraftEventTrace> draftEvents,
                                 long durationMs, String model) {
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
        try {
            List<Conversation.Turn> history = conversation.recentTurns(properties.agent().routerHistoryTurns());
            SupervisorAgent.SupervisorResult result = supervisor.handle(conversation.getId(), message, history);
            conversationService.recordUserMessage(conversation, message);
            conversationService.recordAssistantMessage(conversation, result.answer());
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            List<ToolCallTrace> toolCalls = trace.toolCalls();
            log.info("Chat turn conversation={} intent={} agents={} tools={} llmCalls={} durationMs={}",
                    conversation.getId(), result.intent(), result.selectedAgents(),
                    toolCalls.stream().map(ToolCallTrace::tool).toList(), trace.llmCalls().size(), durationMs);
            return new ChatTurnResult(conversation.getId(), created, UUID.randomUUID().toString(), result.answer(),
                    result.intent(), result.selectedAgents(), toolCalls.stream().map(ToolCallTrace::tool).toList(),
                    trace.agentSteps(), toolCalls, trace.llmCalls(), trace.pendingDraft(), trace.draftEvents(),
                    durationMs, properties.openai().model());
        } finally {
            ExecutionTrace.end();
            conversation.lock().unlock();
        }
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
