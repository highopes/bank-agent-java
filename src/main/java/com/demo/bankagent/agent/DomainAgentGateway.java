package com.demo.bankagent.agent;

/** Executes one task with a specific domain agent. Separated from the Supervisor for testing. */
public interface DomainAgentGateway {

    String execute(AgentType agent, String conversationId, String task);
}
