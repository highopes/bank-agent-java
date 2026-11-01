package com.demo.bankagent.api;

import com.demo.bankagent.config.BankAgentProperties;
import com.demo.bankagent.conversation.ConversationService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Non-sensitive runtime status for the UI. The API key value is never exposed. */
@RestController
public class StatusController {

    private final BankAgentProperties properties;
    private final ConversationService conversationService;

    public StatusController(BankAgentProperties properties, ConversationService conversationService) {
        this.properties = properties;
        this.conversationService = conversationService;
    }

    @GetMapping("/api/status")
    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("application", "bank-agent-java");
        status.put("phase", "Phase 1 - local business application");
        status.put("llmProvider", "OpenAI");
        status.put("llmConfigured", properties.openai().isConfigured());
        status.put("model", properties.openai().model());
        status.put("reasoningEffort", properties.openai().reasoningEffort());
        status.put("activeConversations", conversationService.activeCount());
        status.put("singleTransferLimit", properties.payments().singleTransferLimit());
        status.put("draftTtlMinutes", properties.payments().draftTtl().toMinutes());
        status.put("timeZone", properties.demo().timeZone());
        status.put("javaVersion", Runtime.version().toString());
        return status;
    }
}
