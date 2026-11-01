package com.demo.bankagent.support;

import com.demo.bankagent.config.BankAgentProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.ZoneId;

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
    public ScriptedChatModel scriptedChatModel() {
        return new ScriptedChatModel();
    }
}
