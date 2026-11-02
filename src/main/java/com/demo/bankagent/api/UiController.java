package com.demo.bankagent.api;

import com.demo.bankagent.config.BankAgentProperties;
import com.demo.bankagent.observability.AoSettings;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/** Serves the single-page chat UI. Only non-sensitive values are passed to the template. */
@Controller
public class UiController {

    private final BankAgentProperties properties;
    private final AoSettings ao;

    public UiController(BankAgentProperties properties, AoSettings ao) {
        this.properties = properties;
        this.ao = ao;
    }

    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("model", properties.openai().model());
        model.addAttribute("llmConfigured", properties.openai().isConfigured());
        model.addAttribute("transferLimit", properties.payments().singleTransferLimit().toPlainString());
        model.addAttribute("observabilityEnabled", ao.observabilityEnabled());
        model.addAttribute("controlMode", ao.controlMode().key());
        model.addAttribute("demoTraceContent", ao.demoTraceContent());
        return "index";
    }
}
