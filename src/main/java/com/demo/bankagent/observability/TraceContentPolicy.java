package com.demo.bankagent.observability;

import com.demo.bankagent.llm.SecretMasker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Decides what text may leave the process in the spans this application writes (LangChain4jOtelBridge, the two
 * business spans, control spans). Default (DEMO_TRACE_CONTENT=false): no
 * prompt, answer, tool argument value, beneficiary or amount is exported; spans carry a fixed placeholder (or only
 * argument NAMES) so that AO still shows a valid input/output. Opt-in (DEMO_TRACE_CONTENT=true, fictional demo data
 * only): text is masked (API keys, bearer tokens, long digit runs such as account numbers) and truncated.
 */
@Component
public class TraceContentPolicy {

    public static final String OMITTED = "[omitted: DEMO_TRACE_CONTENT=false]";
    private static final Pattern LONG_DIGITS = Pattern.compile("\\d{8,}");

    private final boolean capture;
    private final int maxChars;
    private final ObjectMapper mapper;

    @Autowired
    public TraceContentPolicy(AoSettings settings, ObservabilityProperties tuning, ObjectMapper mapper) {
        this(settings.demoTraceContent(), tuning.maxContentChars(), mapper);
    }

    public TraceContentPolicy(boolean capture, int maxChars, ObjectMapper mapper) {
        this.capture = capture;
        this.maxChars = maxChars;
        this.mapper = mapper;
    }

    /** Free text (prompt, answer) as it may appear in a span. */
    public String text(String raw) {
        if (!capture) {
            return OMITTED;
        }
        return sanitize(raw);
    }

    /** OpenTelemetry GenAI message list: [{"role":..,"parts":[{"type":"text","content":..}],"finish_reason":..}]. */
    public String messages(String role, String rawText, String finishReason) {
        return messagesRaw(role, text(rawText), finishReason);
    }

    /** Same structure for application-generated, non-sensitive text (e.g. a control result); not gated. */
    public String messagesRaw(String role, String text, String finishReason) {
        ArrayNode list = mapper.createArrayNode();
        ObjectNode message = list.addObject();
        message.put("role", role);
        message.putArray("parts").addObject().put("type", "text").put("content", text == null ? "" : text);
        if (finishReason != null) {
            message.put("finish_reason", finishReason);
        }
        return list.toString();
    }

    /** Tool call arguments (JSON object). Without opt-in only the argument names are exported. */
    public String toolArguments(JsonNode arguments) {
        if (!capture) {
            ObjectNode out = mapper.createObjectNode().put("captured", false);
            ArrayNode names = out.putArray("argumentNames");
            if (arguments != null && arguments.isObject()) {
                arguments.fieldNames().forEachRemaining(names::add);
            }
            return out.toString();
        }
        return arguments == null ? "{}" : sanitize(arguments.toString());
    }

    /** Tool result. Without opt-in only its size is exported. */
    public String toolResult(String result) {
        if (!capture) {
            return mapper.createObjectNode().put("captured", false)
                    .put("resultChars", result == null ? 0 : result.length()).toString();
        }
        String clean = sanitize(result);
        try {
            JsonNode parsed = mapper.readTree(clean);
            return parsed.isObject() ? clean : mapper.createObjectNode().put("value", clean).toString();
        } catch (Exception e) {
            return mapper.createObjectNode().put("value", clean).toString();
        }
    }

    /** Masks secrets and long digit runs and truncates. Used for every exported free-text value. */
    public String sanitize(String raw) {
        if (raw == null) {
            return "";
        }
        String masked = SecretMasker.mask(raw);
        masked = LONG_DIGITS.matcher(masked).replaceAll(m -> "****" + m.group().substring(m.group().length() - 4));
        if (masked.length() > maxChars) {
            masked = masked.substring(0, maxChars) + "...[truncated]";
        }
        return masked;
    }

    /**
     * GenAI input message list for a model request. Without opt-in a single placeholder message is exported.
     * With opt-in the newest messages are kept within a character budget so that the JSON is never cut in the
     * middle by the span attribute length limit.
     */
    public String chatMessages(List<ChatMessage> messages) {
        if (!capture) {
            return messages("user", null, null);
        }
        int budget = Math.max(2000, maxChars * 6);
        List<ObjectNode> kept = new ArrayList<>();
        int used = 0;
        for (int i = messages.size() - 1; i >= 0; i--) {
            ObjectNode node = chatMessage(messages.get(i));
            int size = node.toString().length();
            if (!kept.isEmpty() && used + size > budget) {
                break;
            }
            kept.add(0, node);
            used += size;
        }
        ArrayNode list = mapper.createArrayNode();
        kept.forEach(list::add);
        return list.toString();
    }

    /** GenAI output message for a model response: text answer or tool-call request(s). */
    public String aiOutput(AiMessage message, String finishReason) {
        if (message == null) {
            return messagesRaw("assistant", "", finishReason);
        }
        if (!capture) {
            return messages("assistant", null, finishReason);
        }
        ArrayNode list = mapper.createArrayNode();
        ObjectNode node = chatMessage(message);
        if (finishReason != null) {
            node.put("finish_reason", finishReason);
        }
        list.add(node);
        return list.toString();
    }

    private ObjectNode chatMessage(ChatMessage message) {
        ObjectNode node = mapper.createObjectNode();
        ArrayNode parts = mapper.createArrayNode();
        if (message instanceof SystemMessage system) {
            node.put("role", "system");
            parts.addObject().put("type", "text").put("content", sanitize(system.text()));
        } else if (message instanceof UserMessage user) {
            node.put("role", "user");
            parts.addObject().put("type", "text").put("content", sanitize(userText(user)));
        } else if (message instanceof AiMessage ai) {
            node.put("role", "assistant");
            if (ai.text() != null && !ai.text().isBlank()) {
                parts.addObject().put("type", "text").put("content", sanitize(ai.text()));
            }
            if (ai.hasToolExecutionRequests()) {
                for (ToolExecutionRequest request : ai.toolExecutionRequests()) {
                    parts.addObject().put("type", "tool_call").put("id", request.id()).put("name", request.name())
                            .put("arguments", sanitize(request.arguments()));
                }
            }
        } else if (message instanceof ToolExecutionResultMessage result) {
            node.put("role", "tool");
            parts.addObject().put("type", "tool_call_response").put("id", result.id())
                    .put("response", sanitize(result.text()));
        } else {
            node.put("role", "user");
            parts.addObject().put("type", "text").put("content", sanitize(String.valueOf(message)));
        }
        node.set("parts", parts);
        return node;
    }

    /** Text of a user message, also when it consists of several content parts. */
    public static String userText(UserMessage user) {
        if (user.hasSingleText()) {
            return user.singleText();
        }
        StringBuilder sb = new StringBuilder();
        for (Content content : user.contents()) {
            if (content instanceof TextContent text) {
                sb.append(text.text());
            }
        }
        return sb.toString();
    }
}
