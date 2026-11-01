package com.demo.bankagent.llm;

import com.demo.bankagent.service.BankingException;
import com.demo.bankagent.service.ErrorCode;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

/**
 * Used when no OpenAI API key is configured. The application still starts (UI, data, health),
 * but every chat request fails fast with a clear, non-fabricated error.
 */
public class UnconfiguredChatModel implements ChatModel {

    @Override
    public ChatResponse doChat(ChatRequest chatRequest) {
        throw new BankingException(ErrorCode.LLM_NOT_CONFIGURED,
                "OpenAI is not configured. Set OPENAI_API_KEY in the private secrets file and restart.");
    }
}
