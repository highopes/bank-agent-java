package com.demo.bankagent.llm;

import com.demo.bankagent.config.BankAgentProperties;
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
 */
@Configuration
public class LlmConfig {

    private static final Logger log = LoggerFactory.getLogger(LlmConfig.class);

    @Bean
    public ChatModel chatModel(BankAgentProperties properties) {
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
                .listeners(List.of(new TraceChatModelListener()));
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
