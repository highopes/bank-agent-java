package com.demo.bankagent.api;

import com.demo.bankagent.conversation.ConversationService;
import com.demo.bankagent.service.BankingException;
import com.demo.bankagent.service.ErrorCode;
import com.demo.bankagent.service.PaymentService;
import com.demo.bankagent.service.Views.ConfirmationResult;
import com.demo.bankagent.service.Views.TransferDraftView;
import jakarta.validation.Valid;
import java.util.regex.Pattern;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Explicit, user-triggered transfer decisions. These endpoints are called only by the UI buttons;
 * they are not reachable from the LLM tools.
 */
@RestController
@RequestMapping("/api/payments/drafts")
public class PaymentController {

    private static final Pattern DRAFT_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");

    private final PaymentService paymentService;
    private final ConversationService conversationService;

    public PaymentController(PaymentService paymentService, ConversationService conversationService) {
        this.paymentService = paymentService;
        this.conversationService = conversationService;
    }

    @PostMapping(value = "/{draftId}/confirm", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ConfirmationResult confirm(@PathVariable String draftId,
                                      @Valid @RequestBody ApiRequests.DraftActionRequest request) {
        requireValidId(draftId);
        String conversationId = request.conversationId();
        String customerId = conversationService.requireCustomerId(conversationId);
        try {
            ConfirmationResult result = paymentService.confirm(draftId, conversationId, customerId);
            TransferDraftView d = result.draft();
            conversationService.recordSystemNote(conversationId, result.replayed()
                    ? "User clicked Confirm again for draft " + d.draftId() + "; it was already executed, no extra debit."
                    : "User clicked Confirm in the UI: transfer of " + d.amount().toPlainString() + " " + d.currency()
                    + " to " + d.beneficiaryName() + " was EXECUTED (simulated). New balance "
                    + result.balanceAfter().toPlainString() + " " + d.currency() + ".");
            return result;
        } catch (BankingException e) {
            conversationService.recordSystemNote(conversationId, "User clicked Confirm for draft " + draftId
                    + " but it was rejected: " + e.getCode() + " - " + e.getMessage());
            throw e;
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

    private static void requireValidId(String draftId) {
        if (draftId == null || !DRAFT_ID.matcher(draftId).matches()) {
            throw new BankingException(ErrorCode.DRAFT_NOT_FOUND, "Transfer draft not found.");
        }
    }
}
