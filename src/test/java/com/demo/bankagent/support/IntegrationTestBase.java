package com.demo.bankagent.support;

import com.demo.bankagent.config.BankAgentProperties;
import com.demo.bankagent.service.DemoResetService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;

/**
 * Shared Spring context for offline integration tests: in-memory H2, fixed clock (2026-03-15 10:00,
 * Asia/Shanghai, so "last month" is February 2026) and a scripted chat model. Data and conversations are
 * reset before every test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestSupportConfig.class)
public abstract class IntegrationTestBase {

    public static final LocalDateTime FIXED_NOW = LocalDateTime.of(2026, 3, 15, 10, 0);

    @Autowired
    protected MutableClock clock;

    @Autowired
    protected ScriptedChatModel model;

    @Autowired
    protected DemoResetService demoResetService;

    @Autowired
    protected BankAgentProperties properties;

    protected String customerId() {
        return properties.demo().customerId();
    }

    @BeforeEach
    void resetState() {
        clock.set(FIXED_NOW);
        model.reset();
        demoResetService.reset();
    }
}
