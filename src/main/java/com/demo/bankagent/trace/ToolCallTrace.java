package com.demo.bankagent.trace;

/** One real @Tool invocation captured from LangChain4j's afterToolExecution hook. */
public record ToolCallTrace(String agent, String tool, String arguments, long durationMs, boolean success) {
}
