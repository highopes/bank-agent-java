package com.demo.bankagent.support;

import com.demo.bankagent.observability.DraftTraceRegistry;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * Integration tests of the Galileo instrumentation (Observe + Control): the real application context with spans recorded in memory and
 * a scripted Control gateway. CONTROL_MODE / OBSERVABILITY_ENABLED stay off, so no backend is ever contacted.
 */
@Import({TestSupportConfig.class, InMemoryTelemetryConfig.class, GovernanceTestBase.ScriptedControlConfig.class})
public abstract class GovernanceTestBase extends IntegrationTestBase {

    @Autowired
    protected InMemorySpanExporter spans;

    @Autowired
    protected ScriptedControlGateway control;

    @Autowired
    protected DraftTraceRegistry draftTraces;

    @BeforeEach
    void resetGovernance() {
        spans.reset();
        control.reset();
        draftTraces.clear();
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
