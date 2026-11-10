package com.demo.bankagent.agent;

import com.demo.bankagent.conversation.Conversation;
import com.demo.bankagent.trace.AgentStepTrace;
import com.demo.bankagent.trace.ExecutionTrace;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Supervisor: asks the LLM router which agents are needed, delegates the self-contained tasks
 * sequentially and aggregates the answers. It contains no instrumentation code: the chat turn span is opened by
 * ChatService and the router / agent / tool spans come from LangChain4j listeners.
 */
@Component
public class SupervisorAgent {

    private static final Logger log = LoggerFactory.getLogger(SupervisorAgent.class);

    private final IntentRouter router;
    private final DomainAgentGateway gateway;

    public SupervisorAgent(IntentRouter router, DomainAgentGateway gateway) {
        this.router = router;
        this.gateway = gateway;
    }

    public record SupervisorResult(String intent, List<String> selectedAgents, String answer,
                                   RoutingDecision decision) {
    }

    public SupervisorResult handle(String conversationId, String message, List<Conversation.Turn> history) {
        RoutingDecision decision = router.route(message, history);
        log.info("Supervisor decision conversation={} intent={} agents={} fallback={}", conversationId,
                decision.intent(), decision.steps().stream().map(s -> s.agent().name()).toList(), decision.fallback());
        if (decision.intent() != RoutingDecision.Intent.AGENTS) {
            return new SupervisorResult(decision.intent().name(), List.of(), decision.reply(), decision);
        }
        List<RoutingDecision.Step> steps = distinctByAgent(decision.steps());
        List<String> sections = new ArrayList<>();
        List<String> agents = new ArrayList<>();
        for (RoutingDecision.Step step : steps) {
            long start = System.nanoTime();
            String status = "OK";
            try {
                String answer = gateway.execute(step.agent(), conversationId,
                        agentInput(message, step.task(), steps.size() > 1));
                sections.add(steps.size() > 1
                        ? "**" + step.agent().sectionTitle() + "**\n" + safe(answer)
                        : safe(answer));
            } catch (RuntimeException e) {
                status = "FAILED";
                throw e;
            } finally {
                long ms = (System.nanoTime() - start) / 1_000_000;
                String finalStatus = status;
                ExecutionTrace.current().ifPresent(
                        t -> t.addAgentStep(new AgentStepTrace(step.agent().name(), step.task(), ms, finalStatus)));
            }
            if (!agents.contains(step.agent().name())) {
                agents.add(step.agent().name());
            }
        }
        return new SupervisorResult(decision.intent().name(), List.copyOf(agents), String.join("\n\n", sections),
                decision);
    }

    /**
     * Builds the domain agent input. With a single agent, the user's verbatim message is authoritative and the
     * Supervisor task only resolves references. With several agents, each agent gets only its own task, so it
     * does not try to answer parts owned by other agents. Transfer amounts are always guarded deterministically
     * against the verbatim user message (see PaymentTools), independent of this wording.
     */
    static String agentInput(String userMessage, String task, boolean multiAgent) {
        String note = task == null || task.isBlank() ? "(none)" : task.strip();
        if (multiAgent) {
            return "[Your part - other agents answer the rest of the request in the same reply]\n" + note;
        }
        return "[User message - authoritative]\n" + userMessage.strip()
                + "\n\n[Supervisor note - resolves references from earlier turns. Never do anything the user "
                + "message does not ask for.]\n" + note;
    }

    /** At most one step per agent; a repeated agent would only duplicate work and side effects. */
    static List<RoutingDecision.Step> distinctByAgent(List<RoutingDecision.Step> steps) {
        List<RoutingDecision.Step> result = new ArrayList<>();
        for (RoutingDecision.Step step : steps) {
            if (result.stream().noneMatch(s -> s.agent() == step.agent())) {
                result.add(step);
            }
        }
        return result;
    }

    private static String safe(String answer) {
        return answer == null || answer.isBlank() ? "（该 Agent 没有返回内容）" : answer.trim();
    }
}
