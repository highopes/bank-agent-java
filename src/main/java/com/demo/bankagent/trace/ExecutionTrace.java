package com.demo.bankagent.trace;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-request record of what the backend really executed (agents, tools, LLM calls, drafts, Agent Control checks).
 * It is bound to the request thread and returned to the UI debug panel. This is plain diagnostics, not
 * OpenTelemetry: Phase 2 spans are created separately and only the trace ID is copied here for the UI.
 */
public final class ExecutionTrace {

    private static final ThreadLocal<ExecutionTrace> CURRENT = new ThreadLocal<>();

    private final String conversationId;
    private final List<AgentStepTrace> agentSteps = Collections.synchronizedList(new ArrayList<>());
    private final List<ToolCallTrace> toolCalls = Collections.synchronizedList(new ArrayList<>());
    private final List<LlmCallTrace> llmCalls = Collections.synchronizedList(new ArrayList<>());
    private final List<DraftEventTrace> draftEvents = Collections.synchronizedList(new ArrayList<>());
    private final List<ControlDecisionTrace> controlDecisions = Collections.synchronizedList(new ArrayList<>());
    private final Set<String> steeredWriteSteps = ConcurrentHashMap.newKeySet();
    private volatile String currentCaller = "supervisor";
    private volatile Object pendingDraft;
    private volatile String userMessage;
    private volatile String traceId;

    private ExecutionTrace(String conversationId) {
        this.conversationId = conversationId;
    }

    public static ExecutionTrace begin(String conversationId) {
        ExecutionTrace trace = new ExecutionTrace(conversationId);
        CURRENT.set(trace);
        return trace;
    }

    public static Optional<ExecutionTrace> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static void end() {
        CURRENT.remove();
    }

    public String conversationId() {
        return conversationId;
    }

    public String currentCaller() {
        return currentCaller;
    }

    public void setCurrentCaller(String caller) {
        this.currentCaller = caller;
    }

    public void addAgentStep(AgentStepTrace step) {
        agentSteps.add(step);
    }

    public void addToolCall(ToolCallTrace call) {
        toolCalls.add(call);
    }

    public void addLlmCall(LlmCallTrace call) {
        llmCalls.add(call);
    }

    public void addDraftEvent(DraftEventTrace event) {
        draftEvents.add(event);
    }

    public void setPendingDraft(Object draft) {
        this.pendingDraft = draft;
    }

    /** The user's verbatim message of the current turn (used by deterministic safety guards). */
    public void setUserMessage(String userMessage) {
        this.userMessage = userMessage;
    }

    public String userMessage() {
        return userMessage;
    }

    public Object pendingDraft() {
        return pendingDraft;
    }

    public List<AgentStepTrace> agentSteps() {
        synchronized (agentSteps) {
            return List.copyOf(agentSteps);
        }
    }

    public List<ToolCallTrace> toolCalls() {
        synchronized (toolCalls) {
            return List.copyOf(toolCalls);
        }
    }

    public List<LlmCallTrace> llmCalls() {
        synchronized (llmCalls) {
            return List.copyOf(llmCalls);
        }
    }

    public List<DraftEventTrace> draftEvents() {
        synchronized (draftEvents) {
            return List.copyOf(draftEvents);
        }
    }

    public void addControlDecision(ControlDecisionTrace decision) {
        controlDecisions.add(decision);
    }

    public List<ControlDecisionTrace> controlDecisions() {
        synchronized (controlDecisions) {
            return List.copyOf(controlDecisions);
        }
    }

    /** OpenTelemetry trace ID of this request (null when Observe is off). */
    public String traceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    /** A write step got a STEER decision in this turn: further automatic attempts are blocked until the user acts. */
    public void markSteeredWriteStep(String stepName) {
        steeredWriteSteps.add(stepName);
    }

    public boolean isSteeredWriteStep(String stepName) {
        return steeredWriteSteps.contains(stepName);
    }
}
