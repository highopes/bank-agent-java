package com.demo.bankagent.service;

import com.demo.bankagent.conversation.ConversationService;
import org.springframework.stereotype.Service;

/** Restores the original demo data and forgets every in-memory conversation and agent memory. */
@Service
public class DemoResetService {

    private final DemoDataService demoDataService;
    private final ConversationService conversationService;

    public DemoResetService(DemoDataService demoDataService, ConversationService conversationService) {
        this.demoDataService = demoDataService;
        this.conversationService = conversationService;
    }

    public void reset() {
        demoDataService.reset();
        conversationService.evictAll();
    }
}
