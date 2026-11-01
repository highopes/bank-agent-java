package com.demo.bankagent.agent;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.memory.ChatMemoryAccess;

/**
 * LangChain4j AI service for transfer drafts. It can create, validate and cancel drafts but has no
 * tool that executes a transfer; execution requires the explicit UI confirmation API.
 */
public interface PaymentsAgent extends ChatMemoryAccess {

    String chat(@MemoryId String conversationId, @UserMessage String task);
}
