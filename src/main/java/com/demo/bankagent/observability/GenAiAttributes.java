package com.demo.bankagent.observability;

/**
 * Span attribute names used by this application. The gen_ai.* names follow the OpenTelemetry GenAI semantic
 * conventions; together with galileo.span.kind / agent_control.* they are exactly the wire attributes that the
 * official splunk-ao-python SDK (f7a20ed, converter/attribute_mapping.py) emits in o11y mode, which the protocol verification confirmed
 * are parsed by Splunk AO into agent / llm / tool / control spans. bank.* attributes are application metadata
 * (shown by AO as span metadata).
 */
public final class GenAiAttributes {

    private GenAiAttributes() {
    }

    public static final String OPERATION_NAME = "gen_ai.operation.name";
    public static final String PROVIDER_NAME = "gen_ai.provider.name";
    public static final String CONVERSATION_ID = "gen_ai.conversation.id";
    public static final String AGENT_NAME = "gen_ai.agent.name";
    public static final String AGENT_DESCRIPTION = "gen_ai.agent.description";
    public static final String REQUEST_MODEL = "gen_ai.request.model";
    public static final String RESPONSE_MODEL = "gen_ai.response.model";
    public static final String RESPONSE_ID = "gen_ai.response.id";
    public static final String RESPONSE_FINISH_REASONS = "gen_ai.response.finish_reasons";
    public static final String REQUEST_MAX_TOKENS = "gen_ai.request.max_tokens";
    public static final String USAGE_INPUT_TOKENS = "gen_ai.usage.input_tokens";
    public static final String USAGE_OUTPUT_TOKENS = "gen_ai.usage.output_tokens";
    public static final String INPUT_MESSAGES = "gen_ai.input.messages";
    public static final String OUTPUT_MESSAGES = "gen_ai.output.messages";
    public static final String TOOL_NAME = "gen_ai.tool.name";
    public static final String TOOL_CALL_ID = "gen_ai.tool.call.id";
    public static final String TOOL_CALL_ARGUMENTS = "gen_ai.tool.call.arguments";
    public static final String TOOL_CALL_RESULT = "gen_ai.tool.call.result";
    public static final String ERROR_TYPE = "error.type";
    /**
     * Integer step status (HTTP-like), the attribute the official SDK sets for a step status_code
     * (attribute_mapping.set_common_attributes). Validation: AO keeps it (like the OTel status) only as span
     * metadata; the record's status_code stayed 0, so the failure is visible via metadata, not via status_code.
     */
    public static final String AO_STATUS_CODE = "splunk_ao.status_code";

    public static final String OP_INVOKE_AGENT = "invoke_agent";
    public static final String OP_CHAT = "chat";
    public static final String OP_EXECUTE_TOOL = "execute_tool";
    public static final String OP_CONTROL = "control";
    public static final String PROVIDER_OPENAI = "openai";

    // Control span (splunk-ao-python set_control_attributes).
    public static final String SPAN_KIND = "galileo.span.kind";
    public static final String CONTROL_ID = "agent_control.control_id";
    public static final String CONTROL_NAME = "agent_control.control_name";
    public static final String CONTROL_AGENT_NAME = "agent_control.agent_name";
    public static final String CONTROL_CHECK_STAGE = "agent_control.check_stage";
    public static final String CONTROL_APPLIES_TO = "agent_control.applies_to";
    public static final String CONTROL_EVALUATOR_NAME = "agent_control.evaluator_name";
    public static final String CONTROL_SELECTOR_PATH = "agent_control.selector_path";
    public static final String CONTROL_ACTION = "agent_control.action";
    public static final String CONTROL_MATCHED = "agent_control.matched";
    public static final String CONTROL_CONFIDENCE = "agent_control.confidence";
    public static final String CONTROL_ERROR_MESSAGE = "agent_control.error_message";
    public static final String CONTROL_EXECUTION_ID = "agent_control.control_execution_id";

    // Application metadata.
    public static final String BANK_INTENT = "bank.intent";
    public static final String BANK_SELECTED_AGENTS = "bank.selected_agents";
    public static final String BANK_ROUTE_FALLBACK = "bank.route.fallback";
    public static final String BANK_OPERATION = "bank.operation";
    public static final String BANK_DRAFT_REF = "bank.draft.ref";
    public static final String BANK_OUTCOME = "bank.outcome";
    public static final String BANK_CONTENT_CAPTURED = "bank.content.captured";
    public static final String BANK_STEER_ATTEMPT = "bank.control.steer_attempt";
}
