package com.demo.bankagent.tool;

import com.demo.bankagent.conversation.ConversationService;
import com.demo.bankagent.service.BankingException;
import com.demo.bankagent.service.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Shared helpers for @Tool classes: server-side customer resolution from the conversation id and
 * JSON serialization of real service results returned to the model.
 */
@Component
public class ToolSupport {

    private final ConversationService conversationService;
    private final ObjectMapper objectMapper;

    public ToolSupport(ConversationService conversationService, ObjectMapper objectMapper) {
        this.conversationService = conversationService;
        this.objectMapper = objectMapper;
    }

    /** The customer is always derived from the server-side conversation, never from model arguments. */
    public String customerId(String conversationId) {
        return conversationService.requireCustomerId(conversationId);
    }

    public String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new BankingException(ErrorCode.INTERNAL_ERROR, "Failed to serialize tool result.");
        }
    }

    public String json(String key, Object value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(key, value);
        return json(map);
    }

    public static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() || "null".equalsIgnoreCase(trimmed) ? null : trimmed;
    }

    public static LocalDate parseDate(String value, String field) {
        String v = blankToNull(value);
        if (v == null) {
            return null;
        }
        try {
            return LocalDate.parse(v);
        } catch (DateTimeParseException e) {
            throw new BankingException(ErrorCode.VALIDATION_ERROR, field + " must use the yyyy-MM-dd format.");
        }
    }
}
