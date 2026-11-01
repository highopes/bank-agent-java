package com.demo.bankagent.agent;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.memory.ChatMemoryAccess;

/** LangChain4j AI service for balances, account overview, beneficiaries and payment methods. */
public interface AccountAgent extends ChatMemoryAccess {

    String chat(@MemoryId String conversationId, @UserMessage String task);
}
