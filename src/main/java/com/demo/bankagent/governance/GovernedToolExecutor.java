package com.demo.bankagent.governance;

import com.demo.bankagent.control.ControlOutcome;
import com.demo.bankagent.control.ControlStage;
import com.demo.bankagent.control.ControlStep;
import com.demo.bankagent.control.ControlledStepRunner;
import com.demo.bankagent.control.StepAccess;
import com.demo.bankagent.observability.AgentSpanRunner;
import com.demo.bankagent.observability.BankTelemetry;
import com.demo.bankagent.observability.GenAiAttributes;
import com.demo.bankagent.observability.TraceContentPolicy;
import com.demo.bankagent.trace.ExecutionTrace;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.service.tool.ToolExecutionResult;
import dev.langchain4j.service.tool.ToolExecutor;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Scope;
import java.util.Optional;

/**
 * Wraps LangChain4j's DefaultToolExecutor for one @Tool method: "execute_tool &lt;name&gt;" span started before the
 * Java method runs, Control PRE before the method (so a DENY prevents the side effect), Control POST after it.
 * LangChain4j's own afterToolExecution callback (UI execution trace) keeps working unchanged; it is only a
 * post-execution hook and could never stop a write that already happened.
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
    private final BankTelemetry telemetry;
    private final TraceContentPolicy content;
    private final ControlledStepRunner runner;
    private final ObjectMapper mapper;
    private final ToolSideEffects sideEffects;

    /** Hooks for tools with business side effects (implemented by the tool layer). */
    public interface ToolSideEffects {
        /** Called after a successful execution, inside the tool span. */
        void afterSuccess(String toolName, String conversationId, String resultText, Span toolSpan);

        /** Withdraws the effect of a write whose output was blocked; returns a note for the model, if any. */
        Optional<String> withdraw(String toolName, String conversationId, String resultText, String reason);
    }

    public GovernedToolExecutor(ToolExecutor delegate, String toolName, StepAccess access, BankTelemetry telemetry,
                                TraceContentPolicy content, ControlledStepRunner runner, ObjectMapper mapper,
                                ToolSideEffects sideEffects) {
        this.delegate = delegate;
        this.toolName = toolName;
        this.access = access;
        this.telemetry = telemetry;
        this.content = content;
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
        Span span = telemetry.tracer().spanBuilder("execute_tool " + toolName)
                .setSpanKind(SpanKind.INTERNAL)
                .setAttribute(GenAiAttributes.OPERATION_NAME, GenAiAttributes.OP_EXECUTE_TOOL)
                .setAttribute(GenAiAttributes.TOOL_NAME, toolName)
                .setAttribute(GenAiAttributes.TOOL_CALL_ID, request.id() == null ? "" : request.id())
                .setAttribute(GenAiAttributes.TOOL_CALL_ARGUMENTS, content.toolArguments(arguments))
                .setAttribute(GenAiAttributes.CONVERSATION_ID, conversationId == null ? "" : conversationId)
                .setAttribute("bank.tool.access", access.name())
                .startSpan();
        try (Scope ignored = span.makeCurrent()) {
            Optional<ExecutionTrace> trace = ExecutionTrace.current();
            if (access == StepAccess.WRITE && trace.map(t -> t.isSteeredWriteStep(toolName)).orElse(false)) {
                return blocked(span, "CONTROL_STEER_PENDING_USER", "Agent Control asked for a change before '"
                        + toolName + "' can run in this turn. Ask the user to adjust the request explicitly; do not "
                        + "retry automatically.", null);
            }
            ControlStep step = ControlStep.tool(toolName, arguments, null, conversationId, access);
            ControlOutcome pre = runner.check(ControlStage.PRE, step);
            switch (pre.verdict()) {
                case DENY:
                    return blocked(span, "CONTROL_DENIED", "Agent Control denied this tool call"
                            + controlName(pre) + ": " + pre.decision().reason() + ". The tool was NOT executed. "
                            + "Explain this to the user; do not try to work around it.", pre);
                case STEER:
                    if (access == StepAccess.WRITE) {
                        trace.ifPresent(t -> t.markSteeredWriteStep(toolName));
                        return blocked(span, "CONTROL_STEER", "Agent Control requires a change" + controlName(pre)
                                + ": " + pre.steeringContext() + ". The tool was NOT executed. Tell the user what to "
                                + "change and let them send a new request; never change amounts or beneficiaries "
                                + "yourself.", pre);
                    }
                    return blocked(span, "CONTROL_STEER", "Agent Control guidance" + controlName(pre) + ": "
                            + pre.steeringContext() + ". The tool was NOT executed with these arguments.", pre);
                case BLOCKED_UNAVAILABLE:
                    return blocked(span, "CONTROL_UNAVAILABLE", "The safety check service is unavailable, so '"
                            + toolName + "' was not executed (fail-closed). Tell the user to retry later. This is not "
                            + "a policy decision.", pre);
                default:
                    break;
            }

            ToolExecutionResult result = delegate.executeWithContext(request, context);
            String text = result.resultText();
            span.setAttribute(GenAiAttributes.TOOL_CALL_RESULT, content.toolResult(text));
            if (result.isError()) {
                AgentSpanRunner.markError(span, "tool_error", "tool returned an error result", 500);
                return result;
            }
            if (sideEffects != null) {
                sideEffects.afterSuccess(toolName, conversationId, text, span);
            }

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
                note = sideEffects.withdraw(toolName, conversationId, text, withheldReason)
                        .map(n -> " " + n).orElse("");
            }
            String code = post.verdict() == ControlOutcome.Verdict.STEER ? "CONTROL_STEER_OUTPUT"
                    : post.verdict() == ControlOutcome.Verdict.DENY ? "CONTROL_DENIED_OUTPUT" : "CONTROL_UNAVAILABLE";
            return blocked(span, code, withheldReason + ". The tool output is not shown." + note
                    + " Do not guess the withheld data.", post);
        } catch (RuntimeException e) {
            AgentSpanRunner.recordFailure(span, e);
            throw e;
        } finally {
            span.end();
        }
    }

    private ToolExecutionResult blocked(Span span, String code, String message, ControlOutcome outcome) {
        ObjectNode body = mapper.createObjectNode().put("error", code).put("message", message);
        if (outcome != null && outcome.decision().controlName() != null) {
            body.put("control", outcome.decision().controlName());
        }
        span.setAttribute("bank.control.blocked", true);
        span.setAttribute(GenAiAttributes.TOOL_CALL_RESULT, body.toString());
        int status = code.startsWith("CONTROL_DENIED") ? 403 : code.startsWith("CONTROL_STEER") ? 409 : 503;
        AgentSpanRunner.markError(span, code.toLowerCase(), code, status);
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
