package com.demo.bankagent.trace;

/** One chat-model round trip captured from a ChatModelListener. Token counts may be null. */
public record LlmCallTrace(String caller, String model, Integer inputTokens, Integer outputTokens,
                           String finishReason, long durationMs, boolean success) {
}
