package com.demo.bankagent.observability;

/**
 * Span attribute names used by the hand-written parts of the instrumentation (LangChain4jOtelBridge, the two
 * business spans and the Control span reporter). The gen_ai.* names follow the OpenTelemetry GenAI semantic
 * conventions; with galileo.span.kind / agent_control.* they are the wire attributes that the official
 * splunk-ao-python SDK (f7a20ed, converter/attribute_mapping.py) emits in o11y mode and that Splunk AO parses into
 * agent / llm / tool / control spans (docs/galileo/PROTOCOL-VERIFICATION.md). bank.* attributes are application
 * metadata (AO shows them as span metadata).
 */
public final class GenAiAttributes {

    private GenAiAttributes() {
    }

    public static final String OPERATION_NAME = "gen_ai.operation.name";
    public static final String PROVIDER_NAME = "gen_ai.provider.name";
    public static final String CONVERSATION_ID = "gen_ai.conversation.id";
    public static final String AGENT_NAME = "gen_ai.agent.name";
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
    public static final String BANK_DRAFT_REF = "bank.draft.ref";
    public static final String BANK_OUTCOME = "bank.outcome";
    /** Set by the Control layer on the step it blocked (CONTROL_DENIED, CONTROL_STEER, CONTROL_UNAVAILABLE, ...). */
    public static final String BANK_CONTROL_BLOCKED = "bank.control.blocked";
    public static final String BANK_CONTROL_STEER_RETRIES = "bank.control.steer_retries";
}
