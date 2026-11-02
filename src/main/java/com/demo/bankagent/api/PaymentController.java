package com.demo.bankagent.api;

import com.demo.bankagent.control.ControlExceptions.ControlDeniedException;
import com.demo.bankagent.control.ControlExceptions.ControlSteerRejectedException;
import com.demo.bankagent.control.ControlExceptions.ControlUnavailableException;
import com.demo.bankagent.control.ControlOutcome;
import com.demo.bankagent.control.ControlStage;
import com.demo.bankagent.control.ControlStep;
import com.demo.bankagent.control.ControlledStepRunner;
import com.demo.bankagent.control.StepAccess;
import com.demo.bankagent.control.StepCatalog;
import com.demo.bankagent.conversation.ConversationService;
import com.demo.bankagent.observability.AgentSpanRunner;
import com.demo.bankagent.observability.AoIds;
import com.demo.bankagent.observability.BankTelemetry;
import com.demo.bankagent.observability.DraftTraceRegistry;
import com.demo.bankagent.observability.GenAiAttributes;
import com.demo.bankagent.observability.TraceContentPolicy;
import com.demo.bankagent.service.BankingException;
import com.demo.bankagent.service.ErrorCode;
import com.demo.bankagent.service.PaymentService;
import com.demo.bankagent.service.Views.ConfirmationResult;
import com.demo.bankagent.service.Views.TransferDraftView;
import com.demo.bankagent.tool.TransferToolSideEffects;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Scope;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Explicit, user-triggered transfer decisions. These endpoints are called only by the UI buttons;
 * they are not reachable from the LLM tools.
 *
 * <p>Phase 2: a confirmation is its own trace ("payment.confirm", root span, same AO session via
 * gen_ai.conversation.id, span LINK to the span that created the draft - not a fake child of the old chat turn).
 * Agent Control step "payment.confirm" (write, default fail-closed) is checked BEFORE PaymentService.confirm, so a
 * DENY, a STEER or an unavailable Control service can never cause a debit. The POST check is audit-only: a decision
 * after the debit is recorded, never used to pretend the transfer did not happen.
 */
@RestController
@RequestMapping("/api/payments/drafts")
public class PaymentController {

    /** Response headers that correlate a confirm/cancel click with its AO trace (only when tracing is on). */
    public static final String TRACE_ID_HEADER = "X-Bank-Trace-Id";
    public static final String AO_TRACE_ID_HEADER = "X-Bank-AO-Trace-Id";

    private static final Logger log = LoggerFactory.getLogger(PaymentController.class);
    private static final Pattern DRAFT_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");

    private final PaymentService paymentService;
    private final ConversationService conversationService;
    private final ControlledStepRunner control;
    private final BankTelemetry telemetry;
    private final TraceContentPolicy content;
    private final DraftTraceRegistry draftTraces;
    private final ObjectMapper mapper;

    public PaymentController(PaymentService paymentService, ConversationService conversationService,
                             ControlledStepRunner control, BankTelemetry telemetry, TraceContentPolicy content,
                             DraftTraceRegistry draftTraces, ObjectMapper mapper) {
        this.paymentService = paymentService;
        this.conversationService = conversationService;
        this.control = control;
        this.telemetry = telemetry;
        this.content = content;
        this.draftTraces = draftTraces;
        this.mapper = mapper;
    }

    @PostMapping(value = "/{draftId}/confirm", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ConfirmationResult confirm(@PathVariable String draftId,
                                      @Valid @RequestBody ApiRequests.DraftActionRequest request,
                                      HttpServletResponse response) {
        Span span = businessSpan(StepCatalog.PAYMENT_CONFIRM, "confirm", draftId, request.conversationId());
        try (Scope ignored = span.makeCurrent()) {
            traceHeaders(span, response, "confirm");
            requireValidId(draftId);
            String conversationId = request.conversationId();
            String customerId = conversationService.requireCustomerId(conversationId);
            ControlStep step = confirmStep(draftId, conversationId, customerId);
            if (step != null) {
                enforcePreConfirm(step, draftId, conversationId, customerId);
            }
            try {
                ConfirmationResult result = paymentService.confirm(draftId, conversationId, customerId);
                TransferDraftView d = result.draft();
                conversationService.recordSystemNote(conversationId, result.replayed()
                        ? "User clicked Confirm again for draft " + d.draftId() + "; it was already executed, no extra debit."
                        : "User clicked Confirm in the UI: transfer of " + d.amount().toPlainString() + " " + d.currency()
                        + " to " + d.beneficiaryName() + " was EXECUTED (simulated). New balance "
                        + result.balanceAfter().toPlainString() + " " + d.currency() + ".");
                span.setAttribute(GenAiAttributes.BANK_OUTCOME, result.replayed() ? "REPLAYED" : "EXECUTED");
                span.setAttribute(GenAiAttributes.TOOL_CALL_RESULT, content.toolResult(
                        "{\"status\":\"" + d.status() + "\",\"replayed\":" + result.replayed() + "}"));
                if (step != null) {
                    // Audit-only: the debit already happened and is never rolled back by a POST decision.
                    ObjectNode output = mapper.createObjectNode().put("status", d.status())
                            .put("replayed", result.replayed());
                    control.check(ControlStage.POST, step.withOutput(output));
                }
                return result;
            } catch (BankingException e) {
                conversationService.recordSystemNote(conversationId, "User clicked Confirm for draft " + draftId
                        + " but it was rejected: " + e.getCode() + " - " + e.getMessage());
                throw e;
            }
        } catch (RuntimeException e) {
            span.setAttribute(GenAiAttributes.BANK_OUTCOME, e instanceof BankingException be ? be.getCode().name()
                    : "ERROR");
            AgentSpanRunner.recordFailure(span, e);
            throw e;
        } finally {
            span.end();
        }
    }

    @PostMapping(value = "/{draftId}/cancel", consumes = MediaType.APPLICATION_JSON_VALUE)
    public TransferDraftView cancel(@PathVariable String draftId,
                                    @Valid @RequestBody ApiRequests.DraftActionRequest request,
                                    HttpServletResponse response) {
        Span span = businessSpan("payment.cancel", "cancel", draftId, request.conversationId());
        try (Scope ignored = span.makeCurrent()) {
            traceHeaders(span, response, "cancel");
            requireValidId(draftId);
            String conversationId = request.conversationId();
            String customerId = conversationService.requireCustomerId(conversationId);
            TransferDraftView view = paymentService.cancel(draftId, conversationId, customerId,
                    "Cancelled by the user in the UI.");
            conversationService.recordSystemNote(conversationId, "User clicked Cancel in the UI: transfer draft to "
                    + view.beneficiaryName() + " of " + view.amount().toPlainString() + " " + view.currency()
                    + " is now " + view.status() + ". No money was moved.");
            span.setAttribute(GenAiAttributes.BANK_OUTCOME, view.status());
            return view;
        } catch (RuntimeException e) {
            AgentSpanRunner.recordFailure(span, e);
            throw e;
        } finally {
            span.end();
        }
    }

    /** Control input for the confirmation: only for a pending draft of this conversation (else confirm() rejects). */
    private ControlStep confirmStep(String draftId, String conversationId, String customerId) {
        if (!control.enabled()) {
            return null;
        }
        Optional<TransferDraftView> draft = paymentService.findOwnedDraft(draftId, conversationId, customerId)
                .filter(d -> "PENDING_CONFIRMATION".equals(d.status()));
        if (draft.isEmpty()) {
            return null;
        }
        TransferDraftView d = draft.get();
        ObjectNode input = mapper.createObjectNode()
                .put("draftRef", TransferToolSideEffects.draftRef(d.draftId()))
                .put("beneficiary", d.beneficiaryName())
                .put("beneficiaryId", d.beneficiaryId())
                .put("amount", d.amount().toPlainString())
                .put("currency", d.currency());
        return ControlStep.tool(StepCatalog.PAYMENT_CONFIRM, input, null, conversationId, StepAccess.WRITE);
    }

    private void enforcePreConfirm(ControlStep step, String draftId, String conversationId, String customerId) {
        ControlOutcome pre = control.check(ControlStage.PRE, step);
        RuntimeException blocked = switch (pre.verdict()) {
            case DENY -> new ControlDeniedException(ControlStage.PRE, StepCatalog.PAYMENT_CONFIRM, pre.decision());
            // A STEER on a write is never applied automatically: the user must create a new, adjusted draft.
            case STEER -> new ControlSteerRejectedException(ControlStage.PRE, StepCatalog.PAYMENT_CONFIRM,
                    pre.decision());
            case BLOCKED_UNAVAILABLE -> new ControlUnavailableException(ControlStage.PRE, StepCatalog.PAYMENT_CONFIRM,
                    pre.decision());
            default -> null;
        };
        if (blocked == null) {
            return;
        }
        String code = ((BankingException) blocked).getCode().name();
        paymentService.auditControlRejectedConfirmation(draftId, conversationId, customerId, code
                        + (pre.decision().controlName() == null ? "" : ":" + pre.decision().controlName()),
                "Confirmation not executed: " + blocked.getMessage());
        throw blocked;
    }

    private static void traceHeaders(Span span, HttpServletResponse response, String operation) {
        if (!span.getSpanContext().isValid()) {
            return;
        }
        String traceId = span.getSpanContext().getTraceId();
        response.setHeader(TRACE_ID_HEADER, traceId);
        response.setHeader(AO_TRACE_ID_HEADER, AoIds.aoTraceUuid(traceId));
        log.info("Payment {} traceId={}", operation, traceId);
    }

    private Span businessSpan(String name, String operation, String draftId, String conversationId) {
        SpanBuilder builder = telemetry.tracer().spanBuilder(name)
                .setNoParent()
                .setSpanKind(SpanKind.INTERNAL)
                .setAttribute(GenAiAttributes.OPERATION_NAME, GenAiAttributes.OP_EXECUTE_TOOL)
                .setAttribute(GenAiAttributes.TOOL_NAME, name)
                .setAttribute(GenAiAttributes.BANK_OPERATION, operation)
                .setAttribute(GenAiAttributes.CONVERSATION_ID, conversationId == null ? "" : conversationId)
                .setAttribute(GenAiAttributes.TOOL_CALL_ARGUMENTS, content.toolArguments(mapper.createObjectNode()
                        .put("draftId", draftId == null ? "" : draftId)));
        if (draftId != null && DRAFT_ID.matcher(draftId).matches()) {
            builder.setAttribute(GenAiAttributes.BANK_DRAFT_REF, TransferToolSideEffects.draftRef(draftId));
            draftTraces.creationSpan(draftId).ifPresent(builder::addLink);
        }
        return builder.startSpan();
    }

    private static void requireValidId(String draftId) {
        if (draftId == null || !DRAFT_ID.matcher(draftId).matches()) {
            throw new BankingException(ErrorCode.DRAFT_NOT_FOUND, "Transfer draft not found.");
        }
    }
}
