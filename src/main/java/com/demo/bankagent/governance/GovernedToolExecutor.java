package com.demo.bankagent.governance;

import com.demo.bankagent.control.ControlOutcome;
import com.demo.bankagent.control.ControlStage;
import com.demo.bankagent.control.ControlStep;
import com.demo.bankagent.control.ControlledStepRunner;
import com.demo.bankagent.control.StepAccess;
import com.demo.bankagent.observability.GenAiAttributes;
import com.demo.bankagent.trace.ExecutionTrace;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.service.tool.ToolExecutionResult;
import dev.langchain4j.service.tool.ToolExecutor;
import io.opentelemetry.api.trace.Span;
import java.util.Optional;

/**
 * Agent Control enforcement around one {@code @Tool} method (Control only; created by {@link StepGovernance} when
 * CONTROL_MODE=remote-api). It wraps LangChain4j's DefaultToolExecutor: Control PRE runs before the Java method (so
 * a DENY prevents the side effect), Control POST after it. It creates no span - LangChain4j's
 * beforeToolExecution hook has already opened the {@code execute_tool} span (LangChain4jOtelBridge), so the
 * Control spans nest under it. LangChain4j's afterToolExecution hook is only a post-execution notification and
 * could never stop a write that already happened; that is why this executor exists.
 *
 * <p>Blocked steps return an error tool result (JSON) to the model instead of the tool output, like business
 * errors already do, so the agent can explain the block. For write tools, a STEER blocks further automatic attempts
 * of that tool in the same turn (the user must adjust the request), and a POST deny / steer / fail-closed withdraws
 * a just-created transfer draft and withholds its details. Nothing is rolled back silently: drafts are cancelled
 * with an audit record.
 */
public class GovernedToolExecutor implements ToolExecutor {

    private final ToolExecutor delegate;
    private final String toolName;
    private final StepAccess access;
    private final ControlledStepRunner runner;
    private final ObjectMapper mapper;
    private final ToolSideEffects sideEffects;

    /** Compensation for tools with business side effects (implemented by the tool layer). */
    public interface ToolSideEffects {
        /** Withdraws the effect of a write whose output was blocked; returns a note for the model, if any. */
        Optional<String> withdraw(String toolName, String conversationId, String resultText, String reason);
    }

    public GovernedToolExecutor(ToolExecutor delegate, String toolName, StepAccess access,
                                ControlledStepRunner runner, ObjectMapper mapper, ToolSideEffects sideEffects) {
        this.delegate = delegate;
        this.toolName = toolName;
        this.access = access;
        this.runner = runner;
        this.mapper = mapper;
        this.sideEffects = sideEffects;
    }

    @Override
    public String execute(ToolExecutionRequest request, Object memoryId) {
        return executeWithContext(request, InvocationContext.builder().chatMemoryId(memoryId).build()).resultText();
    }

    @Override
    public ToolExecutionResult executeWithContext(ToolExecutionRequest request, InvocationContext context) {
        String conversationId = context == null || context.chatMemoryId() == null ? null
                : String.valueOf(context.chatMemoryId());
        JsonNode arguments = parse(request.arguments());
        Optional<ExecutionTrace> trace = ExecutionTrace.current();
        if (access == StepAccess.WRITE && trace.map(t -> t.isSteeredWriteStep(toolName)).orElse(false)) {
            return blocked("CONTROL_STEER_PENDING_USER", "Agent Control asked for a change before '" + toolName
                    + "' can run in this turn. Ask the user to adjust the request explicitly; do not retry "
                    + "automatically.", null);
        }
        ControlStep step = ControlStep.tool(toolName, arguments, null, conversationId, access);
        ControlOutcome pre = runner.check(ControlStage.PRE, step);
        switch (pre.verdict()) {
            case DENY:
                return blocked("CONTROL_DENIED", "Agent Control denied this tool call" + controlName(pre) + ": "
                        + pre.decision().reason() + ". The tool was NOT executed. Explain this to the user; do not "
                        + "try to work around it.", pre);
            case STEER:
                if (access == StepAccess.WRITE) {
                    trace.ifPresent(t -> t.markSteeredWriteStep(toolName));
                    return blocked("CONTROL_STEER", "Agent Control requires a change" + controlName(pre) + ": "
                            + pre.steeringContext() + ". The tool was NOT executed. Tell the user what to change and "
                            + "let them send a new request; never change amounts or beneficiaries yourself.", pre);
                }
                return blocked("CONTROL_STEER", "Agent Control guidance" + controlName(pre) + ": "
                        + pre.steeringContext() + ". The tool was NOT executed with these arguments.", pre);
            case BLOCKED_UNAVAILABLE:
                return blocked("CONTROL_UNAVAILABLE", "The safety check service is unavailable, so '" + toolName
                        + "' was not executed (fail-closed). Tell the user to retry later. This is not a policy "
                        + "decision.", pre);
            default:
                break;
        }

        ToolExecutionResult result = delegate.executeWithContext(request, context);
        if (result.isError()) {
            return result;
        }
        String text = result.resultText();
        ControlOutcome post = runner.check(ControlStage.POST, step.withOutput(parse(text)));
        String withheldReason = switch (post.verdict()) {
            case DENY -> "Agent Control denied the output of '" + toolName + "'" + controlName(post) + ": "
                    + post.decision().reason();
            case STEER -> "Agent Control withheld the output of '" + toolName + "'" + controlName(post)
                    + ". Guidance: " + post.steeringContext();
            case BLOCKED_UNAVAILABLE -> "The safety check service is unavailable, so the output of '" + toolName
                    + "' is withheld (fail-closed)";
            default -> null;
        };
        if (withheldReason == null) {
            return result;
        }
        String note = "";
        if (access == StepAccess.WRITE && sideEffects != null) {
            note = sideEffects.withdraw(toolName, conversationId, text, withheldReason).map(n -> " " + n).orElse("");
        }
        String code = post.verdict() == ControlOutcome.Verdict.STEER ? "CONTROL_STEER_OUTPUT"
                : post.verdict() == ControlOutcome.Verdict.DENY ? "CONTROL_DENIED_OUTPUT" : "CONTROL_UNAVAILABLE";
        return blocked(code, withheldReason + ". The tool output is not shown." + note
                + " Do not guess the withheld data.", post);
    }

    /** Error result for the model; the current tool span gets the blocking code as metadata. */
    private ToolExecutionResult blocked(String code, String message, ControlOutcome outcome) {
        ObjectNode body = mapper.createObjectNode().put("error", code).put("message", message);
        if (outcome != null && outcome.decision().controlName() != null) {
            body.put("control", outcome.decision().controlName());
        }
        Span.current().setAttribute(GenAiAttributes.BANK_CONTROL_BLOCKED, code);
        return ToolExecutionResult.builder().isError(true).resultText(body.toString()).build();
    }

    private static String controlName(ControlOutcome outcome) {
        String name = outcome.decision().controlName();
        return name == null ? "" : " (control '" + name + "')";
    }

    private JsonNode parse(String json) {
        if (json == null || json.isBlank()) {
            return mapper.createObjectNode();
        }
        try {
            JsonNode node = mapper.readTree(json);
            return node == null ? mapper.createObjectNode() : node;
        } catch (Exception e) {
            return mapper.getNodeFactory().textNode(json);
        }
    }
}
