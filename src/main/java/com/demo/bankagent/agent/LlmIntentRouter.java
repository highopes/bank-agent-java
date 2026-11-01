package com.demo.bankagent.agent;

import com.demo.bankagent.config.BankAgentProperties;
import com.demo.bankagent.conversation.Conversation;
import com.demo.bankagent.llm.LlmErrorTranslator;
import com.demo.bankagent.trace.ExecutionTrace;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * OpenAI-backed intent classification. The model returns JSON constrained by a strict JSON Schema;
 * the result is validated against Java enums. Invalid output falls back to a clarification, never to
 * keyword matching.
 */
@Component
public class LlmIntentRouter implements IntentRouter {

    private static final Logger log = LoggerFactory.getLogger(LlmIntentRouter.class);
    private static final int MAX_TURN_CHARS = 600;

    static final ResponseFormat RESPONSE_FORMAT = buildResponseFormat();

    private final ChatModel chatModel;
    private final AgentPrompts prompts;
    private final BankAgentProperties properties;
    private final ObjectMapper objectMapper;

    public LlmIntentRouter(ChatModel chatModel, AgentPrompts prompts, BankAgentProperties properties,
                           ObjectMapper objectMapper) {
        this.chatModel = chatModel;
        this.prompts = prompts;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public RoutingDecision route(String message, List<Conversation.Turn> history) {
        ExecutionTrace.current().ifPresent(t -> t.setCurrentCaller("supervisor"));
        ChatRequest request = ChatRequest.builder()
                .messages(SystemMessage.from(prompts.supervisorPrompt()),
                        UserMessage.from(renderInput(message, history)))
                .responseFormat(RESPONSE_FORMAT)
                .build();
        ChatResponse response;
        try {
            response = chatModel.chat(request);
        } catch (RuntimeException e) {
            throw LlmErrorTranslator.translate(e);
        }
        String json = response.aiMessage() == null ? null : response.aiMessage().text();
        return parse(json);
    }

    RoutingDecision parse(String json) {
        if (json == null || json.isBlank()) {
            log.warn("Supervisor returned an empty routing decision; falling back to CLARIFY");
            return RoutingDecision.clarifyFallback();
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            RoutingDecision.Intent intent = RoutingDecision.Intent.valueOf(
                    root.path("intent").asText("").trim().toUpperCase(Locale.ROOT));
            String reply = root.path("reply").asText("").trim();
            if (intent != RoutingDecision.Intent.AGENTS) {
                return new RoutingDecision(intent, List.of(),
                        reply.isEmpty() ? RoutingDecision.clarifyFallback().reply() : reply, false);
            }
            List<RoutingDecision.Step> steps = new ArrayList<>();
            for (JsonNode node : root.path("steps")) {
                if (steps.size() >= properties.agent().maxRouteSteps()) {
                    break;
                }
                AgentType agent = AgentType.valueOf(node.path("agent").asText("").trim().toUpperCase(Locale.ROOT));
                String task = node.path("task").asText("").trim();
                if (task.isEmpty()) {
                    throw new IllegalArgumentException("empty task for " + agent);
                }
                steps.add(new RoutingDecision.Step(agent, task));
            }
            if (steps.isEmpty()) {
                log.warn("Supervisor chose AGENTS without steps; falling back to CLARIFY");
                return RoutingDecision.clarifyFallback();
            }
            return new RoutingDecision(intent, List.copyOf(steps), "", false);
        } catch (Exception e) {
            log.warn("Supervisor returned an invalid routing decision ({}); falling back to CLARIFY", e.getMessage());
            return RoutingDecision.clarifyFallback();
        }
    }

    private String renderInput(String message, List<Conversation.Turn> history) {
        StringBuilder sb = new StringBuilder("Conversation history (oldest first):\n");
        if (history.isEmpty()) {
            sb.append("(none)\n");
        }
        for (Conversation.Turn turn : history) {
            String role = switch (turn.role()) {
                case USER -> "User";
                case ASSISTANT -> "Assistant";
                case SYSTEM_NOTE -> "System note";
            };
            String text = turn.text().length() > MAX_TURN_CHARS
                    ? turn.text().substring(0, MAX_TURN_CHARS) + "..." : turn.text();
            sb.append(role).append(": ").append(text.replace('\n', ' ')).append('\n');
        }
        sb.append("\nLatest user message:\n").append(message);
        return sb.toString();
    }

    private static ResponseFormat buildResponseFormat() {
        JsonObjectSchema step = JsonObjectSchema.builder()
                .addEnumProperty("agent", Arrays.stream(AgentType.values()).map(Enum::name).toList())
                .addStringProperty("task")
                .required("agent", "task")
                .additionalProperties(false)
                .build();
        JsonObjectSchema root = JsonObjectSchema.builder()
                .addEnumProperty("intent", Arrays.stream(RoutingDecision.Intent.values()).map(Enum::name).toList())
                .addProperty("steps", JsonArraySchema.builder().items(step).build())
                .addStringProperty("reply")
                .required("intent", "steps", "reply")
                .additionalProperties(false)
                .build();
        return ResponseFormat.builder()
                .type(ResponseFormatType.JSON)
                .jsonSchema(JsonSchema.builder().name("supervisor_routing").rootElement(root).build())
                .build();
    }
}
