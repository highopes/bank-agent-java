package com.demo.bankagent.tool;

import com.demo.bankagent.service.AccountService;
import com.demo.bankagent.service.AmountMentions;
import com.demo.bankagent.service.AmountParser;
import com.demo.bankagent.service.PaymentService;
import com.demo.bankagent.service.Views.TransferDraftView;
import com.demo.bankagent.trace.DraftEventTrace;
import com.demo.bankagent.trace.ExecutionTrace;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Tools available to the Payments agent. There is intentionally NO tool that executes or confirms a
 * transfer: execution only happens when the user clicks Confirm in the UI (REST API).
 */
@Component
public class PaymentTools {

    private final PaymentService paymentService;
    private final AccountService accountService;
    private final ToolSupport support;

    public PaymentTools(PaymentService paymentService, AccountService accountService, ToolSupport support) {
        this.paymentService = paymentService;
        this.accountService = accountService;
        this.support = support;
    }

    @Tool(name = "listTransferBeneficiaries",
            value = "List the registered beneficiaries that transfers can be sent to.")
    public String listTransferBeneficiaries(@ToolMemoryId String conversationId) {
        return support.json("beneficiaries", accountService.listBeneficiaries(support.customerId(conversationId)));
    }

    @Tool(name = "validateTransfer",
            value = "Check a potential transfer (registered beneficiary, amount format, single transfer limit, "
                    + "available balance) without creating anything. Returns all issues found.")
    public String validateTransfer(
            @ToolMemoryId String conversationId,
            @P("Beneficiary name or id exactly as registered, e.g. 张三 or BEN-001") String beneficiary,
            @P("Amount in CNY as a plain number, e.g. 500 or 1200.50") String amount,
            @P(value = "Source account, default CHECKING", required = false) String fromAccount) {
        String customerId = support.customerId(conversationId);
        return support.json(paymentService.validate(customerId, conversationId, beneficiary, amount,
                ToolSupport.blankToNull(fromAccount)));
    }

    @Tool(name = "createTransferDraft",
            value = "Create a PENDING transfer draft that the user must explicitly confirm by clicking the Confirm "
                    + "button in the UI. It does NOT move money. Any older pending draft in this conversation is replaced.")
    public String createTransferDraft(
            @ToolMemoryId String conversationId,
            @P("Beneficiary name or id exactly as registered, e.g. 张三 or BEN-001") String beneficiary,
            @P("Amount in CNY as a plain number, e.g. 500 or 1200.50") String amount,
            @P(value = "Optional short memo for the transfer", required = false) String memo,
            @P(value = "Source account, default CHECKING", required = false) String fromAccount) {
        String customerId = support.customerId(conversationId);
        Optional<ExecutionTrace> trace = ExecutionTrace.current();
        Optional<BigDecimal> parsed = AmountParser.parse(amount);
        String userMessage = trace.map(ExecutionTrace::userMessage).orElse(null);
        PaymentService.DraftResult result;
        if (parsed.isPresent() && userMessage != null && !AmountMentions.mentions(userMessage, parsed.get())) {
            result = paymentService.rejectUnstatedAmount(customerId, conversationId, beneficiary, parsed.get());
        } else {
            result = paymentService.createDraft(customerId, conversationId, beneficiary, amount,
                    ToolSupport.blankToNull(memo), ToolSupport.blankToNull(fromAccount));
        }
        result.supersededDraftIds().forEach(id -> trace.ifPresent(
                t -> t.addDraftEvent(new DraftEventTrace("SUPERSEDED", id, "CANCELLED"))));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("created", result.created());
        if (result.created()) {
            TransferDraftView draft = result.draft();
            trace.ifPresent(t -> {
                t.setPendingDraft(draft);
                t.addDraftEvent(new DraftEventTrace("CREATED", draft.draftId(), draft.status()));
            });
            out.put("draft", draft);
            out.put("nextStep", "Tell the user the draft is pending and that they must click the Confirm (确认) or "
                    + "Cancel (取消) button on the draft card in the UI. Money has NOT been moved.");
        } else {
            trace.ifPresent(t -> t.addDraftEvent(new DraftEventTrace("REJECTED", null, "REJECTED")));
            out.put("issues", result.issues());
            out.put("nextStep", "Explain every issue to the user in plain words. No draft was created and no money "
                    + "was moved. Do not propose alternative amounts or beneficiaries and do not create another draft.");
        }
        return support.json(out);
    }

    @Tool(name = "cancelPendingTransfer",
            value = "Cancel the latest pending (unconfirmed) transfer draft in this conversation. No money is moved.")
    public String cancelPendingTransfer(
            @ToolMemoryId String conversationId,
            @P(value = "Optional cancellation reason", required = false) String reason) {
        String customerId = support.customerId(conversationId);
        Optional<TransferDraftView> cancelled = paymentService.cancelLatestPending(conversationId, customerId,
                ToolSupport.blankToNull(reason) == null ? "Cancelled by the user via chat." : reason.trim());
        Map<String, Object> out = new LinkedHashMap<>();
        if (cancelled.isPresent()) {
            TransferDraftView draft = cancelled.get();
            ExecutionTrace.current().ifPresent(
                    t -> t.addDraftEvent(new DraftEventTrace("CANCELLED", draft.draftId(), draft.status())));
            out.put("cancelled", "CANCELLED".equals(draft.status()));
            out.put("draft", draft);
        } else {
            out.put("cancelled", false);
            out.put("message", "There is no pending transfer draft in this conversation. Recent drafts may already "
                    + "be executed, cancelled or expired; use getRecentTransferActivity to check.");
        }
        return support.json(out);
    }

    @Tool(name = "getRecentTransferActivity",
            value = "Get recent transfer drafts (with status and failure reason) and the payment audit trail of this "
                    + "conversation. Use it to explain why a transfer failed, was rejected, cancelled or expired.")
    public String getRecentTransferActivity(@ToolMemoryId String conversationId) {
        String customerId = support.customerId(conversationId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("drafts", paymentService.recentDrafts(conversationId, customerId));
        out.put("auditTrail", paymentService.recentAudit(conversationId));
        return support.json(out);
    }
}
