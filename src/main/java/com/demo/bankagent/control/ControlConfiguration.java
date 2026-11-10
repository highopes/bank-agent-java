package com.demo.bankagent.control;

import com.demo.bankagent.observability.AoConfigurationException;
import com.demo.bankagent.observability.AoSettings;
import com.demo.bankagent.observability.AoTarget;
import com.demo.bankagent.observability.AoTargetHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * CONTROL_MODE=off -&gt; {@link NoOpControlGateway} (no network at all). CONTROL_MODE=remote-api -&gt;
 * {@link RemoteAgentControlGateway}, started (health, initAgent, runtime token, control catalog) before the
 * application accepts traffic; if that is impossible the application does not start - it never silently falls back
 * to "off".
 */
@Configuration(proxyBeanMethods = false)
public class ControlConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ControlConfiguration.class);

    @Bean
    public ControlGateway controlGateway(AoSettings settings, AoTargetHolder targets, ControlProperties props,
                                         ObjectMapper mapper, StepCatalog catalog,
                                         @Value("${bank-agent.observability.service-version:dev}") String version) {
        if (!settings.controlEnabled()) {
            log.info("Agent Control: CONTROL_MODE=off - no control checks and no Agent Control requests.");
            return new NoOpControlGateway();
        }
        AoTarget target = targets.target().orElseThrow(() -> new AoConfigurationException(
                "CONTROL_MODE=remote-api requires a resolved Agent Stream ID."));
        RemoteAgentControlGateway gateway = new RemoteAgentControlGateway(settings, target, props, mapper, catalog,
                version);
        try {
            gateway.start();
        } catch (ControlTransportException e) {
            throw new AoConfigurationException("CONTROL_MODE=remote-api, but Agent Control at "
                    + AoSettings.host(settings.agentControlUrl().value()) + " is not usable (" + e.code() + "): "
                    + e.getMessage() + ". Check AGENT_CONTROL_URL / credentials, or set CONTROL_MODE=off. "
                    + "See docs/galileo/AGENT-CONTROL.md.");
        }
        if (!settings.observabilityEnabled()) {
            log.warn("Agent Control is on but Observe is off: decisions are enforced and shown in the UI, but no "
                    + "control spans can be exported (no OpenTelemetry Java agent), so the Console Controls tab "
                    + "stays empty ({}).", ControlEventReporter.EVENT_INGEST_UNAVAILABLE);
        }
        return gateway;
    }
}
