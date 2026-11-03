package com.demo.bankagent.llm;

import com.demo.bankagent.trace.ExecutionTrace;
import com.demo.bankagent.trace.LlmCallTrace;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Records every real chat-model round trip (model, token usage, latency) into the request's
 * ExecutionTrace. It never logs prompt or completion text. It only fires after a call, so LLM spans and LLM
 * Control are done by GovernedChatModel, which wraps the model and runs before the call.
 */
public class TraceChatModelListener implements ChatModelListener {

    private static final Logger log = LoggerFactory.getLogger(TraceChatModelListener.class);
    private static final String START_KEY = "bankagent.startNanos";

    @Override
    public void onRequest(ChatModelRequestContext context) {
        context.attributes().put(START_KEY, System.nanoTime());
    }

    @Override
    public void onResponse(ChatModelResponseContext context) {
        long durationMs = elapsedMs(context.attributes().get(START_KEY));
        ChatResponse response = context.chatResponse();
        TokenUsage usage = response.tokenUsage();
        String caller = ExecutionTrace.current().map(ExecutionTrace::currentCaller).orElse("unknown");
        LlmCallTrace call = new LlmCallTrace(caller, response.modelName(),
                usage == null ? null : usage.inputTokenCount(), usage == null ? null : usage.outputTokenCount(),
                response.finishReason() == null ? null : response.finishReason().name(), durationMs, true);
        ExecutionTrace.current().ifPresent(t -> t.addLlmCall(call));
        log.info("LLM call caller={} model={} inputTokens={} outputTokens={} finish={} durationMs={}",
                caller, call.model(), call.inputTokens(), call.outputTokens(), call.finishReason(), durationMs);
    }

    @Override
    public void onError(ChatModelErrorContext context) {
        long durationMs = elapsedMs(context.attributes().get(START_KEY));
        String caller = ExecutionTrace.current().map(ExecutionTrace::currentCaller).orElse("unknown");
        ExecutionTrace.current().ifPresent(t -> t.addLlmCall(
                new LlmCallTrace(caller, null, null, null, "ERROR", durationMs, false)));
        log.warn("LLM call failed caller={} durationMs={} error={}", caller, durationMs,
                SecretMasker.mask(String.valueOf(context.error().getMessage())));
    }

    private static long elapsedMs(Object startNanos) {
        return startNanos instanceof Long start ? (System.nanoTime() - start) / 1_000_000 : -1;
    }
}
