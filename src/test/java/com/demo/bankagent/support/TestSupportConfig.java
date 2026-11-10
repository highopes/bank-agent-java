package com.demo.bankagent.support;

import com.demo.bankagent.config.BankAgentProperties;
import com.demo.bankagent.llm.TraceChatModelListener;
import com.demo.bankagent.observability.LangChain4jOtelBridge;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.ZoneId;
import java.util.List;

/** Replaces the system clock and the OpenAI chat model with deterministic offline test doubles. */
@TestConfiguration(proxyBeanMethods = false)
public class TestSupportConfig {

    @Bean
    @Primary
    public MutableClock testClock(BankAgentProperties properties) {
        return new MutableClock(IntegrationTestBase.FIXED_NOW, ZoneId.of(properties.demo().timeZone()));
    }

    @Bean
    @Primary
    public ScriptedChatModel scriptedChatModel(LangChain4jOtelBridge otel) {
        // Same listeners as the production OpenAI model (LlmConfig): UI execution trace + GenAI spans.
        return new ScriptedChatModel(List.of(new TraceChatModelListener(), otel));
    }
}
