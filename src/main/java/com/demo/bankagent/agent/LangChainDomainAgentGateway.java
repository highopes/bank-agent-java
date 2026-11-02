package com.demo.bankagent.agent;

import com.demo.bankagent.llm.LlmErrorTranslator;
import com.demo.bankagent.observability.AgentSpanRunner;
import com.demo.bankagent.trace.ExecutionTrace;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import org.springframework.stereotype.Component;

/**
 * Dispatches to the LangChain4j AI services. Each agent has its own per-conversation memory.
 *
 * <p>Phase 2: every delegation runs inside an "invoke_agent &lt;Name&gt;Agent" span that is started before the AI
 * service is called, so its LLM and tool spans become children of the right agent.
 */
@Component
public class LangChainDomainAgentGateway implements DomainAgentGateway {

    private final AccountAgent accountAgent;
    private final TransactionsAgent transactionsAgent;
    private final PaymentsAgent paymentsAgent;
    private final AgentSpanRunner spans;

    public LangChainDomainAgentGateway(AccountAgent accountAgent, TransactionsAgent transactionsAgent,
                                       PaymentsAgent paymentsAgent, AgentSpanRunner spans) {
        this.accountAgent = accountAgent;
        this.transactionsAgent = transactionsAgent;
        this.paymentsAgent = paymentsAgent;
        this.spans = spans;
    }

    @Override
    public String execute(AgentType agent, String conversationId, String task) {
        ExecutionTrace.current().ifPresent(t -> t.setCurrentCaller(agent.name()));
        Span span = spans.startAgent(spanAgentName(agent), conversationId, task);
        try (Scope ignored = span.makeCurrent()) {
            String answer = switch (agent) {
                case ACCOUNT -> accountAgent.chat(conversationId, task);
                case TRANSACTIONS -> transactionsAgent.chat(conversationId, task);
                case PAYMENTS -> paymentsAgent.chat(conversationId, task);
            };
            spans.setOutput(span, answer);
            return answer;
        } catch (RuntimeException e) {
            RuntimeException translated = LlmErrorTranslator.translate(e);
            AgentSpanRunner.recordFailure(span, translated);
            throw translated;
        } finally {
            span.end();
            ExecutionTrace.current().ifPresent(t -> t.setCurrentCaller("supervisor"));
        }
    }

    /** "Account Agent" -&gt; "AccountAgent" (gen_ai.agent.name and span name). */
    static String spanAgentName(AgentType agent) {
        return agent.displayName().replace(" ", "");
    }
}
