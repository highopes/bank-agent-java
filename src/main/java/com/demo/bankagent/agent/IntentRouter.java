package com.demo.bankagent.agent;

import com.demo.bankagent.conversation.Conversation;
import java.util.List;

/** Decides which domain agents should handle a user message. */
public interface IntentRouter {

    RoutingDecision route(String message, List<Conversation.Turn> history);
}
