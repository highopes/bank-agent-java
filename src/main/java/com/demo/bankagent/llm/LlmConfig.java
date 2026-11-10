package com.demo.bankagent.llm;

import com.demo.bankagent.config.BankAgentProperties;
import com.demo.bankagent.observability.LangChain4jOtelBridge;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the single ChatModel shared by the Supervisor and the domain agents. Only OpenAI is
 * contacted. No temperature is sent because GPT-5 family reasoning models reject custom values.
 *
 * <p>Two LangChain4j ChatModelListeners are registered: the UI execution trace and {@link LangChain4jOtelBridge}
 * (one GenAI "chat" span per model call, with the provider's token usage; a no-op without the OTel Java agent).
 */
@Configuration
public class LlmConfig {

    private static final Logger log = LoggerFactory.getLogger(LlmConfig.class);

    @Bean
    public ChatModel chatModel(BankAgentProperties properties, LangChain4jOtelBridge otel) {
        BankAgentProperties.OpenAi openai = properties.openai();
        if (!openai.isConfigured()) {
            log.warn("OPENAI_API_KEY is not configured. The UI will start, but chat requests will return "
                    + "LLM_NOT_CONFIGURED until the private secrets file is provided.");
            return new UnconfiguredChatModel();
        }
        OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
                .apiKey(openai.apiKey())
                .modelName(openai.model())
                .maxCompletionTokens(openai.maxCompletionTokens())
                .timeout(openai.timeout())
                .maxRetries(openai.maxRetries())
                .strictJsonSchema(true)
                .store(false)
                .logRequests(openai.logRequests())
                .logResponses(openai.logResponses())
                .listeners(List.of(new TraceChatModelListener(), otel));
        if (openai.baseUrl() != null && !openai.baseUrl().isBlank()) {
            builder.baseUrl(openai.baseUrl());
        }
        if (openai.reasoningEffort() != null && !openai.reasoningEffort().isBlank()) {
            builder.reasoningEffort(openai.reasoningEffort());
        }
        log.info("OpenAI chat model configured: {}", openai);
        return builder.build();
    }
}
