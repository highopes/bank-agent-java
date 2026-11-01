package com.demo.bankagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class BankAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(BankAgentApplication.class, args);
    }
}
