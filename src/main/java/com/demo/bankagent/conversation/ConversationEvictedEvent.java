package com.demo.bankagent.conversation;

/** Published when a conversation expires or is cleared, so per-conversation agent memory can be released. */
public record ConversationEvictedEvent(String conversationId) {
}
