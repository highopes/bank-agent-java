package com.demo.bankagent.service;

import com.demo.bankagent.conversation.ConversationService;
import com.demo.bankagent.observability.DraftTraceRegistry;
import org.springframework.stereotype.Service;

/** Restores the original demo data and forgets every in-memory conversation and agent memory. */
@Service
public class DemoResetService {

    private final DemoDataService demoDataService;
    private final ConversationService conversationService;
    private final DraftTraceRegistry draftTraces;

    public DemoResetService(DemoDataService demoDataService, ConversationService conversationService,
                            DraftTraceRegistry draftTraces) {
        this.demoDataService = demoDataService;
        this.conversationService = conversationService;
        this.draftTraces = draftTraces;
    }

    public void reset() {
        demoDataService.reset();
        conversationService.evictAll();
        draftTraces.clear();
    }
}
