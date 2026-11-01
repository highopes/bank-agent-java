package com.demo.bankagent.agent;

import com.demo.bankagent.conversation.ConversationEvictedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Releases all agent memory of a conversation when it expires or the demo is reset. */
@Component
public class AgentMemoryManager {

    private final AgentMemoryStores stores;
    private final AccountAgent accountAgent;
    private final TransactionsAgent transactionsAgent;
    private final PaymentsAgent paymentsAgent;

    public AgentMemoryManager(AgentMemoryStores stores, AccountAgent accountAgent,
                              TransactionsAgent transactionsAgent, PaymentsAgent paymentsAgent) {
        this.stores = stores;
        this.accountAgent = accountAgent;
        this.transactionsAgent = transactionsAgent;
        this.paymentsAgent = paymentsAgent;
    }

    @EventListener
    public void onConversationEvicted(ConversationEvictedEvent event) {
        String conversationId = event.conversationId();
        accountAgent.evictChatMemory(conversationId);
        transactionsAgent.evictChatMemory(conversationId);
        paymentsAgent.evictChatMemory(conversationId);
        stores.delete(conversationId);
    }
}
