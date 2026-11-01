package com.demo.bankagent.config;

import java.math.BigDecimal;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Strongly typed application settings bound from the {@code bank-agent.*} namespace. */
@ConfigurationProperties(prefix = "bank-agent")
public record BankAgentProperties(OpenAi openai, Agent agent, Payments payments, Demo demo) {

    /**
     * OpenAI settings. The API key is loaded from the private local file or environment;
     * it is never logged or returned by any endpoint.
     */
    public record OpenAi(String apiKey, String baseUrl, String model, String reasoningEffort,
                         Integer maxCompletionTokens, Duration timeout, Integer maxRetries,
                         boolean logRequests, boolean logResponses) {

        public boolean isConfigured() {
            return apiKey != null && !apiKey.isBlank();
        }

        @Override
        public String toString() {
            return "OpenAi[baseUrl=" + baseUrl + ", model=" + model + ", reasoningEffort=" + reasoningEffort
                    + ", apiKey=" + (isConfigured() ? "<set>" : "<missing>") + "]";
        }
    }

    public record Agent(int maxToolInvocations, int memoryMaxMessages, int maxMessageLength,
                        int routerHistoryTurns, int maxRouteSteps, Duration conversationTtl,
                        int maxConversations) {
    }

    public record Payments(BigDecimal singleTransferLimit, Duration draftTtl, String currency) {
    }

    public record Demo(String customerId, String timeZone) {
    }
}
