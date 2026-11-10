package com.demo.bankagent.support;

import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * Integration tests of the Galileo instrumentation with Observe and Control both on: the real application context,
 * spans recorded in memory (in place of the OpenTelemetry Java agent) and a scripted Control gateway, so the
 * PRE/POST enforcement adapters are active. No backend is ever contacted.
 */
@Import({TestSupportConfig.class, InMemoryTelemetryConfig.class, GovernanceTestBase.ScriptedControlConfig.class})
public abstract class GovernanceTestBase extends IntegrationTestBase {

    @Autowired
    protected InMemorySpanExporter spans;

    @Autowired
    protected ScriptedControlGateway control;

    @BeforeEach
    void resetGovernance() {
        spans.reset();
        control.reset();
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class ScriptedControlConfig {
        @Bean
        @Primary
        public ScriptedControlGateway scriptedControlGateway() {
            return new ScriptedControlGateway();
        }
    }
}
