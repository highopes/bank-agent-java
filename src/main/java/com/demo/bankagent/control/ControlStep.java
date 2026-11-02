package com.demo.bankagent.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

/**
 * Project-internal description of the step being checked. This is NOT the product API schema; the remote gateway
 * maps it to the Agent Control EvaluationRequest (agent_name, stage, step{type,name,input,output,context},
 * target_type, target_id). Tool steps must carry an object input (Agent Control Step validation).
 */
public record ControlStep(String type, String name, JsonNode input, JsonNode output, String conversationId,
                          StepAccess access) {

    public static final String TYPE_LLM = "llm";
    public static final String TYPE_TOOL = "tool";

    public static ControlStep llm(String name, String input, String output, String conversationId) {
        return new ControlStep(TYPE_LLM, name, JsonNodeFactory.instance.textNode(input == null ? "" : input),
                output == null ? null : JsonNodeFactory.instance.textNode(output), conversationId, StepAccess.READ);
    }

    public static ControlStep tool(String name, JsonNode input, JsonNode output, String conversationId,
                                   StepAccess access) {
        JsonNode objectInput = input != null && input.isObject() ? input : JsonNodeFactory.instance.objectNode();
        return new ControlStep(TYPE_TOOL, name, objectInput, output, conversationId, access);
    }

    public ControlStep withOutput(JsonNode newOutput) {
        return new ControlStep(type, name, input, newOutput, conversationId, access);
    }

    /** Observability "applies_to" value (agent_control evaluation_events.map_applies_to). */
    public String appliesTo() {
        return switch (type) {
            case TYPE_TOOL -> "tool_call";
            case TYPE_LLM -> "llm_call";
            default -> type + "_call";
        };
    }
}
