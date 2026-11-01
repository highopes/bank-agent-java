package com.demo.bankagent.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfig {

    /** Single time source so that seed data, periods and draft expiry are consistent and testable. */
    @Bean
    public Clock clock(BankAgentProperties properties) {
        return Clock.system(ZoneId.of(properties.demo().timeZone()));
    }
}
