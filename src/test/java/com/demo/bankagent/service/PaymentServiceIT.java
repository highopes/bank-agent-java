package com.demo.bankagent.service;

import com.demo.bankagent.domain.PaymentAudit;
import com.demo.bankagent.domain.PaymentAuditAction;
import com.demo.bankagent.repository.BankTransactionRepository;
import com.demo.bankagent.repository.PaymentAuditRepository;
import com.demo.bankagent.repository.TransferDraftRepository;
import com.demo.bankagent.service.Views.ConfirmationResult;
import com.demo.bankagent.service.Views.TransferDraftView;
import com.demo.bankagent.service.Views.TransferIssue;
import com.demo.bankagent.support.IntegrationTestBase;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentServiceIT extends IntegrationTestBase {

    private static final String CONV = "conv-a";

    @Autowired
    PaymentService payments;

    @Autowired
    AccountService accountService;

    @Autowired
    PaymentAuditRepository audits;

    @Autowired
    BankTransactionRepository transactions;

    @Autowired
    TransferDraftRepository drafts;

    private BigDecimal checkingBalance() {
        return accountService.getBalance(customerId(), "CHECKING").balance();
    }

    private TransferDraftView draft(String conversationId, String beneficiary, String amount) {
        PaymentService.DraftResult result = payments.createDraft(customerId(), conversationId, beneficiary, amount,
                null, null);
        assertThat(result.created()).as("draft created: %s", result.issues()).isTrue();
        return result.draft();
    }

    private static void assertCode(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BankingException.class,
                e -> assertThat(e.getCode()).isEqualTo(code));
    }

    @Test
    void draftDoesNotMoveMoney() {
        long txBefore = transactions.count();
        TransferDraftView d = draft(CONV, "张三", "500");

        assertThat(d.status()).isEqualTo("PENDING_CONFIRMATION");
        assertThat(d.amount()).isEqualByComparingTo("500.00");
        assertThat(d.expiresAt()).isEqualTo(FIXED_NOW.plusMinutes(10));
        assertThat(checkingBalance()).isEqualByComparingTo("52368.50");
        assertThat(transactions.count()).isEqualTo(txBefore);
        assertThat(audits.findByDraftIdOrderByIdAsc(d.draftId())).extracting(PaymentAudit::getAction)
                .containsExactly(PaymentAuditAction.DRAFT_CREATED);
    }

    @Test
    void confirmDebitsOnceAndDuplicateConfirmIsReplayed() {
        TransferDraftView d = draft(CONV, "BEN-001", "500");

        ConfirmationResult first = payments.confirm(d.draftId(), CONV, customerId());
        assertThat(first.replayed()).isFalse();
        assertThat(first.draft().status()).isEqualTo("EXECUTED");
        assertThat(first.balanceAfter()).isEqualByComparingTo("51868.50");
        assertThat(transactions.countByReference(d.draftId())).isEqualTo(1);

        ConfirmationResult second = payments.confirm(d.draftId(), CONV, customerId());
        assertThat(second.replayed()).isTrue();
        assertThat(checkingBalance()).isEqualByComparingTo("51868.50");
        assertThat(transactions.countByReference(d.draftId())).isEqualTo(1);
        assertThat(audits.findByDraftIdOrderByIdAsc(d.draftId())).extracting(PaymentAudit::getAction)
                .containsExactly(PaymentAuditAction.DRAFT_CREATED, PaymentAuditAction.CONFIRM_EXECUTED,
                        PaymentAuditAction.CONFIRM_REPLAYED);
    }

    @Test
    void newDraftSupersedesOlderPendingDraftInSameConversation() {
        TransferDraftView first = draft(CONV, "张三", "100");
        PaymentService.DraftResult second = payments.createDraft(customerId(), CONV, "李四", "200", null, null);

        assertThat(second.supersededDraftIds()).containsExactly(first.draftId());
        assertThat(drafts.findById(first.draftId())).hasValueSatisfying(
                d -> assertThat(d.getStatus().name()).isEqualTo("CANCELLED"));
        assertCode(() -> payments.confirm(first.draftId(), CONV, customerId()), ErrorCode.DRAFT_CANCELLED);
    }

    @Test
    void unregisteredBeneficiaryAndInvalidAmountsAreRejectedAndAudited() {
        PaymentService.DraftResult stranger = payments.createDraft(customerId(), CONV, "赵六", "100", null, null);
        assertThat(stranger.created()).isFalse();
        assertThat(stranger.issues()).extracting(TransferIssue::code).contains("BENEFICIARY_NOT_REGISTERED");

        for (String amount : List.of("0", "-5", "abc", "10.123")) {
            PaymentService.DraftResult invalid = payments.createDraft(customerId(), CONV, "张三", amount, null, null);
            assertThat(invalid.created()).as(amount).isFalse();
            assertThat(invalid.issues()).extracting(TransferIssue::code).as(amount).contains("INVALID_AMOUNT");
        }

        PaymentService.DraftResult overLimit = payments.createDraft(customerId(), CONV, "张三", "50000.01", null, null);
        assertThat(overLimit.issues()).extracting(TransferIssue::code).contains("AMOUNT_EXCEEDS_LIMIT");

        assertThat(drafts.count()).isZero();
        assertThat(payments.recentAudit(CONV)).hasSize(6)
                .allSatisfy(a -> assertThat(a.action()).isEqualTo("DRAFT_REJECTED"));
    }

    @Test
    void insufficientFundsIsRejectedAtDraftTimeAndAtConfirmTime() {
        TransferDraftView big = draft("conv-1", "张三", "45000");
        TransferDraftView competing = draft("conv-2", "李四", "45000");
        payments.confirm(big.draftId(), "conv-1", customerId());
        assertThat(checkingBalance()).isEqualByComparingTo("7368.50");

        PaymentService.DraftResult tooMuch = payments.createDraft(customerId(), "conv-3", "王五", "8000", null, null);
        assertThat(tooMuch.issues()).extracting(TransferIssue::code).contains("INSUFFICIENT_FUNDS");

        assertCode(() -> payments.confirm(competing.draftId(), "conv-2", customerId()), ErrorCode.INSUFFICIENT_FUNDS);
        assertThat(payments.findDraft(competing.draftId())).hasValueSatisfying(
                d -> assertThat(d.status()).isEqualTo("FAILED"));
        assertCode(() -> payments.confirm(competing.draftId(), "conv-2", customerId()), ErrorCode.DRAFT_FAILED);
        assertThat(checkingBalance()).isEqualByComparingTo("7368.50");
    }

    @Test
    void expiredDraftCannotBeConfirmed() {
        TransferDraftView d = draft(CONV, "张三", "300");
        clock.advance(Duration.ofMinutes(11));

        assertCode(() -> payments.confirm(d.draftId(), CONV, customerId()), ErrorCode.DRAFT_EXPIRED);
        assertThat(payments.findDraft(d.draftId())).hasValueSatisfying(v -> assertThat(v.status()).isEqualTo("EXPIRED"));
        assertThat(checkingBalance()).isEqualByComparingTo("52368.50");
    }

    @Test
    void draftsAreBoundToTheirConversation() {
        TransferDraftView d = draft(CONV, "张三", "300");

        assertCode(() -> payments.confirm(d.draftId(), "conv-other", customerId()),
                ErrorCode.DRAFT_CONVERSATION_MISMATCH);
        assertCode(() -> payments.cancel(d.draftId(), "conv-other", customerId(), null),
                ErrorCode.DRAFT_CONVERSATION_MISMATCH);
        assertCode(() -> payments.confirm("does-not-exist", CONV, customerId()), ErrorCode.DRAFT_NOT_FOUND);
        assertThat(payments.findDraft(d.draftId())).hasValueSatisfying(
                v -> assertThat(v.status()).isEqualTo("PENDING_CONFIRMATION"));
        assertThat(audits.findByDraftIdOrderByIdAsc(d.draftId())).extracting(PaymentAudit::getCode)
                .containsSubsequence("DRAFT_CONVERSATION_MISMATCH", "DRAFT_CONVERSATION_MISMATCH");
    }

    @Test
    void cancelledDraftCannotBeConfirmedAndExecutedDraftCannotBeCancelled() {
        TransferDraftView cancelled = draft(CONV, "张三", "300");
        assertThat(payments.cancel(cancelled.draftId(), CONV, customerId(), null).status()).isEqualTo("CANCELLED");
        assertCode(() -> payments.confirm(cancelled.draftId(), CONV, customerId()), ErrorCode.DRAFT_CANCELLED);

        TransferDraftView executed = draft(CONV, "李四", "200");
        payments.confirm(executed.draftId(), CONV, customerId());
        assertCode(() -> payments.cancel(executed.draftId(), CONV, customerId(), null),
                ErrorCode.DRAFT_ALREADY_EXECUTED);
        assertThat(checkingBalance()).isEqualByComparingTo("52168.50");
    }

    @Test
    void unstatedAmountRejectionIsAuditedAndCreatesNoDraft() {
        PaymentService.DraftResult result = payments.rejectUnstatedAmount(customerId(), CONV, "李四",
                new BigDecimal("999999"));

        assertThat(result.created()).isFalse();
        assertThat(result.issues()).extracting(TransferIssue::code)
                .containsExactly(PaymentService.AMOUNT_NOT_STATED_BY_USER);
        assertThat(drafts.count()).isZero();
        assertThat(payments.recentAudit(CONV)).singleElement().satisfies(a -> {
            assertThat(a.action()).isEqualTo("DRAFT_REJECTED");
            assertThat(a.code()).isEqualTo(PaymentService.AMOUNT_NOT_STATED_BY_USER);
        });
    }
}
