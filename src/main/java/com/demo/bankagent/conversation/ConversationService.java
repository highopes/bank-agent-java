package com.demo.bankagent.conversation;

import com.demo.bankagent.config.BankAgentProperties;
import com.demo.bankagent.service.BankingException;
import com.demo.bankagent.service.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * Owns conversation identity. Every conversation is bound server-side to the fixed demo customer,
 * so tools never accept a customer id from the LLM or the browser.
 */
@Service
public class ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);
    private static final int MAX_TRANSCRIPT_TURNS = 40;

    private final Map<String, Conversation> conversations = new ConcurrentHashMap<>();
    private final BankAgentProperties properties;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    public ConversationService(BankAgentProperties properties, Clock clock,
                               ApplicationEventPublisher events) {
        this.properties = properties;
        this.clock = clock;
        this.events = events;
    }

    public Conversation create() {
        purgeExpired();
        enforceCapacity();
        String id = UUID.randomUUID().toString();
        Conversation conversation = new Conversation(id, properties.demo().customerId(), clock.instant());
        conversations.put(id, conversation);
        log.info("Conversation created id={}", id);
        return conversation;
    }

    public Optional<Conversation> find(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return Optional.empty();
        }
        Conversation conversation = conversations.get(conversationId);
        if (conversation == null) {
            return Optional.empty();
        }
        if (isExpired(conversation, clock.instant())) {
            evict(conversationId);
            return Optional.empty();
        }
        return Optional.of(conversation);
    }

    public Conversation require(String conversationId) {
        return find(conversationId).orElseThrow(() -> new BankingException(ErrorCode.CONVERSATION_NOT_FOUND,
                "Conversation not found or expired. Please start a new conversation."));
    }

    public String requireCustomerId(String conversationId) {
        return require(conversationId).getCustomerId();
    }

    public void recordUserMessage(Conversation conversation, String text) {
        Instant now = clock.instant();
        conversation.touch(now);
        conversation.append(Conversation.Role.USER, text, now, MAX_TRANSCRIPT_TURNS);
    }

    public void recordAssistantMessage(Conversation conversation, String text) {
        Instant now = clock.instant();
        conversation.touch(now);
        conversation.append(Conversation.Role.ASSISTANT, text, now, MAX_TRANSCRIPT_TURNS);
    }

    /** Adds a factual note produced by the backend (for example a UI confirmation result). */
    public void recordSystemNote(String conversationId, String text) {
        find(conversationId).ifPresent(c -> {
            Instant now = clock.instant();
            c.touch(now);
            c.append(Conversation.Role.SYSTEM_NOTE, text, now, MAX_TRANSCRIPT_TURNS);
        });
    }

    public int activeCount() {
        return conversations.size();
    }

    public void evictAll() {
        List.copyOf(conversations.keySet()).forEach(this::evict);
        log.info("All conversations cleared");
    }

    private void evict(String conversationId) {
        if (conversations.remove(conversationId) != null) {
            events.publishEvent(new ConversationEvictedEvent(conversationId));
            log.info("Conversation evicted id={}", conversationId);
        }
    }

    private boolean isExpired(Conversation conversation, Instant now) {
        return conversation.getLastActiveAt().plus(properties.agent().conversationTtl()).isBefore(now);
    }

    private void purgeExpired() {
        Instant now = clock.instant();
        conversations.values().stream()
                .filter(c -> isExpired(c, now))
                .map(Conversation::getId)
                .toList()
                .forEach(this::evict);
    }

    private void enforceCapacity() {
        int max = properties.agent().maxConversations();
        if (conversations.size() < max) {
            return;
        }
        conversations.values().stream()
                .sorted(Comparator.comparing(Conversation::getLastActiveAt))
                .limit(conversations.size() - max + 1L)
                .map(Conversation::getId)
                .toList()
                .forEach(this::evict);
    }
}
