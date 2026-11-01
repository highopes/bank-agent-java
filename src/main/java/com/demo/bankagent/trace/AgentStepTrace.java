package com.demo.bankagent.trace;

/** One delegated domain-agent execution recorded by the Supervisor. */
public record AgentStepTrace(String agent, String task, long durationMs, String status) {
}
