package com.demo.bankagent.agent;

import com.demo.bankagent.config.BankAgentProperties;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.store.memory.chat.InMemoryChatMemoryStore;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * One in-memory store per domain agent, keyed by conversation id, so memories of different agents and
 * different conversations never mix. Memory is bounded by a message window to limit token usage.
 */
@Component
public class AgentMemoryStores {

    private final Map<AgentType, InMemoryChatMemoryStore> stores = new EnumMap<>(AgentType.class);
    private final int maxMessages;

    public AgentMemoryStores(BankAgentProperties properties) {
        for (AgentType type : AgentType.values()) {
            stores.put(type, new InMemoryChatMemoryStore());
        }
        this.maxMessages = properties.agent().memoryMaxMessages();
    }

    public ChatMemoryProvider provider(AgentType type) {
        InMemoryChatMemoryStore store = stores.get(type);
        return memoryId -> MessageWindowChatMemory.builder()
                .id(memoryId)
                .maxMessages(maxMessages)
                .chatMemoryStore(store)
                .build();
    }

    public InMemoryChatMemoryStore store(AgentType type) {
        return stores.get(type);
    }

    public void delete(String conversationId) {
        stores.values().forEach(s -> s.deleteMessages(conversationId));
    }
}
