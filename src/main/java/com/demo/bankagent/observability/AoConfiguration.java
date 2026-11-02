package com.demo.bankagent.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Resolves {@link AoSettings} once at startup. When OBSERVABILITY_ENABLED=true or CONTROL_MODE=remote-api and the
 * active profile lacks a required value, startup fails with a message naming the private file, the profile and
 * docs/CONFIG-MAPPING.md. With both switches off nothing is validated and nothing is contacted.
 */
@Configuration(proxyBeanMethods = false)
public class AoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AoConfiguration.class);

    @Bean
    public AoSettings aoSettings(AoRawProperties raw, Environment environment) {
        AoSettings settings = AoEndpointResolver.resolve(raw);
        String file = environment.getProperty("BANK_AGENT_GALILEO_FILE",
                System.getProperty("user.home") + "/.config/bank-agent-java/galileo.properties");
        if (!settings.active()) {
            log.info("Phase 2 Observe and Agent Control are off (OBSERVABILITY_ENABLED=false, CONTROL_MODE=off): "
                    + "no validation, no backend network calls. Settings file: {}", file);
            return settings;
        }
        if (!settings.problems().isEmpty()) {
            throw new AoConfigurationException("Phase 2 configuration is incomplete for profile '"
                    + settings.profile().key() + "' (" + settings.profileSource() + "). Fix "
                    + String.join(" ", settings.problems()) + " Private file: " + file
                    + ". Key reference: docs/CONFIG-MAPPING.md");
        }
        log.info("Phase 2 settings resolved from {} (values with [derived] follow the profile rule, [override] were "
                + "set explicitly; credentials are only shown as set/missing):", file);
        settings.describe().forEach(line -> log.info("  {}", line));
        return settings;
    }
}
