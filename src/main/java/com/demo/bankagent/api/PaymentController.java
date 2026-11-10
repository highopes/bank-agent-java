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
import com.demo.bankagent.observability.AoIds;
import com.demo.bankagent.observability.GenAiAttributes;
import com.demo.bankagent.observability.GenAiSpans;
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
 * <p>Observe: the HTTP request span comes from the OpenTelemetry Java agent. The confirmation additionally gets the
 * second (and last) hand-written span of the application, "execute_tool payment.confirm": the debit is a
 * business step that LangChain4j does not run (no framework event), it carries gen_ai.conversation.id so AO puts
 * this separate trace into the same session as the chat that created the draft, and it is the step under which
 * the Control spans of "payment.confirm" are shown. Cancel has no business span (it is neither an AI step nor
 * controlled): its trace is just the agent's HTTP span.
 *
 * <p>Control: step "payment.confirm" (write, default fail-closed) is checked BEFORE PaymentService.confirm, so a
 * DENY, a STEER or an unavailable Control service can never cause a debit. The POST check is audit-only: a
 * decision after the debit is recorded, never used to pretend the transfer did not happen.
 */
@RestController
@RequestMapping("/api/payments/drafts")
public class PaymentController {

    /** Response headers that correlate a confirm click with its AO trace (only when Observe is on). */
    public static final String TRACE_ID_HEADER = "X-Bank-Trace-Id";
    public static final String AO_TRACE_ID_HEADER = "X-Bank-AO-Trace-Id";

    private static final Logger log = LoggerFactory.getLogger(PaymentController.class);
    private static final Pattern DRAFT_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");

    private final PaymentService paymentService;
    private final ConversationService conversationService;
    private final ControlledStepRunner control;
    private final GenAiSpans spans;
    private final TraceContentPolicy content;
    private final ObjectMapper mapper;

    public PaymentController(PaymentService paymentService, ConversationService conversationService,
                             ControlledStepRunner control, GenAiSpans spans, ObjectMapper mapper) {
        this.paymentService = paymentService;
        this.conversationService = conversationService;
        this.control = control;
        this.spans = spans;
        this.content = spans.content();
        this.mapper = mapper;
    }

    @PostMapping(value = "/{draftId}/confirm", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ConfirmationResult confirm(@PathVariable String draftId,
                                      @Valid @RequestBody ApiRequests.DraftActionRequest request,
                                      HttpServletResponse response) {
        Span span = spans.startTool(StepCatalog.PAYMENT_CONFIRM, request.conversationId(),
                content.toolArguments(mapper.createObjectNode().put("draftId", draftId == null ? "" : draftId)));
        if (draftId != null && DRAFT_ID.matcher(draftId).matches()) {
            span.setAttribute(GenAiAttributes.BANK_DRAFT_REF, TransferToolSideEffects.draftRef(draftId));
        }
        try (Scope ignored = GenAiSpans.makeCurrent(span, request.conversationId())) {
            traceHeaders(span, response);
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
            GenAiSpans.recordFailure(span, e);
            throw e;
        } finally {
            span.end();
        }
    }

    @PostMapping(value = "/{draftId}/cancel", consumes = MediaType.APPLICATION_JSON_VALUE)
    public TransferDraftView cancel(@PathVariable String draftId,
                                    @Valid @RequestBody ApiRequests.DraftActionRequest request) {
        requireValidId(draftId);
        String conversationId = request.conversationId();
        String customerId = conversationService.requireCustomerId(conversationId);
        TransferDraftView view = paymentService.cancel(draftId, conversationId, customerId, "Cancelled by the user in the UI.");
        conversationService.recordSystemNote(conversationId, "User clicked Cancel in the UI: transfer draft to "
                + view.beneficiaryName() + " of " + view.amount().toPlainString() + " " + view.currency()
                + " is now " + view.status() + ". No money was moved.");
        return view;
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

    private static void traceHeaders(Span span, HttpServletResponse response) {
        String traceId = GenAiSpans.traceId(span);
        if (traceId == null) {
            return;
        }
        response.setHeader(TRACE_ID_HEADER, traceId);
        response.setHeader(AO_TRACE_ID_HEADER, AoIds.aoTraceUuid(traceId));
        log.info("Payment confirm traceId={}", traceId);
    }

    private static void requireValidId(String draftId) {
        if (draftId == null || !DRAFT_ID.matcher(draftId).matches()) {
            throw new BankingException(ErrorCode.DRAFT_NOT_FOUND, "Transfer draft not found.");
        }
    }
}
