package com.demo.bankagent.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Seeds the demo data once when the database is empty. It never appends data on later restarts. */
@Component
public class DemoDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataInitializer.class);

    private final DemoDataService demoDataService;

    public DemoDataInitializer(DemoDataService demoDataService) {
        this.demoDataService = demoDataService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!demoDataService.seedIfEmpty()) {
            log.info("Existing demo data found; seeding skipped. Use the reset endpoint or scripts/reset-demo.sh to reseed.");
        }
    }
}
