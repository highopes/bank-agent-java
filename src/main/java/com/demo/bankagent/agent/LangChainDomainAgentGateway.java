package com.demo.bankagent.agent;

import com.demo.bankagent.llm.LlmErrorTranslator;
import com.demo.bankagent.trace.ExecutionTrace;
import org.springframework.stereotype.Component;

/** Dispatches to the LangChain4j AI services. Each agent has its own per-conversation memory. */
@Component
public class LangChainDomainAgentGateway implements DomainAgentGateway {

    private final AccountAgent accountAgent;
    private final TransactionsAgent transactionsAgent;
    private final PaymentsAgent paymentsAgent;

    public LangChainDomainAgentGateway(AccountAgent accountAgent, TransactionsAgent transactionsAgent,
                                       PaymentsAgent paymentsAgent) {
        this.accountAgent = accountAgent;
        this.transactionsAgent = transactionsAgent;
        this.paymentsAgent = paymentsAgent;
    }

    @Override
    public String execute(AgentType agent, String conversationId, String task) {
        ExecutionTrace.current().ifPresent(t -> t.setCurrentCaller(agent.name()));
        try {
            return switch (agent) {
                case ACCOUNT -> accountAgent.chat(conversationId, task);
                case TRANSACTIONS -> transactionsAgent.chat(conversationId, task);
                case PAYMENTS -> paymentsAgent.chat(conversationId, task);
            };
        } catch (RuntimeException e) {
            throw LlmErrorTranslator.translate(e);
        } finally {
            ExecutionTrace.current().ifPresent(t -> t.setCurrentCaller("supervisor"));
        }
    }
}
