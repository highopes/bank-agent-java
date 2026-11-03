package com.demo.bankagent.service;

import com.demo.bankagent.config.BankAgentProperties;
import com.demo.bankagent.domain.AccountType;
import com.demo.bankagent.domain.BankAccount;
import com.demo.bankagent.domain.BankTransaction;
import com.demo.bankagent.domain.Beneficiary;
import com.demo.bankagent.domain.PaymentAudit;
import com.demo.bankagent.domain.PaymentAuditAction;
import com.demo.bankagent.domain.TransactionDirection;
import com.demo.bankagent.domain.TransferDraft;
import com.demo.bankagent.domain.TransferStatus;
import com.demo.bankagent.repository.BankAccountRepository;
import com.demo.bankagent.repository.BankTransactionRepository;
import com.demo.bankagent.repository.BeneficiaryRepository;
import com.demo.bankagent.repository.PaymentAuditRepository;
import com.demo.bankagent.repository.TransferDraftRepository;
import com.demo.bankagent.service.Views.AuditView;
import com.demo.bankagent.service.Views.ConfirmationResult;
import com.demo.bankagent.service.Views.TransferDraftView;
import com.demo.bankagent.service.Views.TransferIssue;
import com.demo.bankagent.service.Views.TransferValidationView;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Simulated transfer workflow. The LLM-facing tools can only validate, create and cancel drafts.
 * {@link #confirm} is called exclusively by the REST controller after an explicit UI click.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final TransferDraftRepository drafts;
    private final BankAccountRepository accounts;
    private final BeneficiaryRepository beneficiaries;
    private final BankTransactionRepository transactions;
    private final PaymentAuditRepository audits;
    private final AccountService accountService;
    private final BankAgentProperties properties;
    private final Clock clock;

    public PaymentService(TransferDraftRepository drafts, BankAccountRepository accounts,
                          BeneficiaryRepository beneficiaries, BankTransactionRepository transactions,
                          PaymentAuditRepository audits, AccountService accountService,
                          BankAgentProperties properties, Clock clock) {
        this.drafts = drafts;
        this.accounts = accounts;
        this.beneficiaries = beneficiaries;
        this.transactions = transactions;
        this.audits = audits;
        this.accountService = accountService;
        this.properties = properties;
        this.clock = clock;
    }

    public static final String AMOUNT_NOT_STATED_BY_USER = "AMOUNT_NOT_STATED_BY_USER";

    /** Result of a draft request: either a created draft or the list of reasons it was rejected. */
    public record DraftResult(boolean created, TransferDraftView draft, List<TransferIssue> issues,
                              List<String> supersededDraftIds) {
    }

    private record Checked(TransferValidationView view, BankAccount account, Beneficiary beneficiary,
                           BigDecimal amount) {
    }

    @Transactional
    public TransferValidationView validate(String customerId, String conversationId, String beneficiaryRef,
                                           String amountText, String fromAccountRef) {
        Checked checked = check(customerId, beneficiaryRef, amountText, fromAccountRef);
        if (!checked.view().valid()) {
            audit(null, conversationId, customerId, PaymentAuditAction.VALIDATION, "REJECTED",
                    displayName(checked, beneficiaryRef), checked.amount(), codes(checked.view().issues()),
                    messages(checked.view().issues()));
        }
        return checked.view();
    }

    @Transactional
    public DraftResult createDraft(String customerId, String conversationId, String beneficiaryRef,
                                   String amountText, String memo, String fromAccountRef) {
        Checked checked = check(customerId, beneficiaryRef, amountText, fromAccountRef);
        if (!checked.view().valid()) {
            audit(null, conversationId, customerId, PaymentAuditAction.DRAFT_REJECTED, "REJECTED",
                    displayName(checked, beneficiaryRef), checked.amount(), codes(checked.view().issues()),
                    messages(checked.view().issues()));
            log.info("Transfer draft rejected conversation={} codes={}", conversationId, codes(checked.view().issues()));
            return new DraftResult(false, null, checked.view().issues(), List.of());
        }
        LocalDateTime now = LocalDateTime.now(clock);
        List<String> superseded = new ArrayList<>();
        for (TransferDraft old : drafts.findByConversationIdAndStatusOrderByCreatedAtDesc(conversationId,
                TransferStatus.PENDING_CONFIRMATION)) {
            old.markCancelled(now, "SUPERSEDED", "Replaced by a newer transfer draft in the same conversation.");
            audit(old.getId(), conversationId, customerId, PaymentAuditAction.DRAFT_SUPERSEDED, "CANCELLED",
                    old.getBeneficiaryName(), old.getAmount(), "SUPERSEDED", "Replaced by a newer draft.");
            superseded.add(old.getId());
        }
        String cleanMemo = memo == null || memo.isBlank() ? null : memo.trim();
        if (cleanMemo != null && cleanMemo.length() > 140) {
            cleanMemo = cleanMemo.substring(0, 140);
        }
        TransferDraft draft = new TransferDraft(UUID.randomUUID().toString(), conversationId, customerId,
                checked.account().getId(), checked.beneficiary().getId(), checked.beneficiary().getName(),
                checked.amount(), checked.account().getCurrency(), cleanMemo, now,
                now.plus(properties.payments().draftTtl()));
        drafts.save(draft);
        audit(draft.getId(), conversationId, customerId, PaymentAuditAction.DRAFT_CREATED, "PENDING",
                draft.getBeneficiaryName(), draft.getAmount(), null, "Awaiting explicit user confirmation in the UI.");
        log.info("Transfer draft created id={} conversation={} amount={}", draft.getId(), conversationId,
                draft.getAmount());
        return new DraftResult(true, toView(draft), List.of(), superseded);
    }

    /**
     * Records a draft request that was blocked before validation because the amount was not stated by the
     * user in the current message (deterministic guard against LLM-invented amounts).
     */
    @Transactional
    public DraftResult rejectUnstatedAmount(String customerId, String conversationId, String beneficiaryRef,
                                            BigDecimal amount) {
        TransferIssue issue = new TransferIssue(AMOUNT_NOT_STATED_BY_USER,
                "The amount " + amount.toPlainString() + " was not stated by the user in the current message. "
                        + "A transfer draft is only created for an amount the user explicitly writes.");
        audit(null, conversationId, customerId, PaymentAuditAction.DRAFT_REJECTED, "REJECTED",
                displayName(new Checked(null, null, null, amount), beneficiaryRef), amount, issue.code(),
                issue.message());
        log.info("Transfer draft blocked conversation={} code={}", conversationId, AMOUNT_NOT_STATED_BY_USER);
        return new DraftResult(false, null, List.of(issue), List.of());
    }

    /** Executes a pending draft. Only the REST controller may call this, after an explicit UI click. */
    @Transactional(noRollbackFor = BankingException.class)
    public ConfirmationResult confirm(String draftId, String conversationId, String customerId) {
        LocalDateTime now = LocalDateTime.now(clock);
        TransferDraft draft = lockOwnedDraft(draftId, conversationId, customerId, PaymentAuditAction.CONFIRM_REJECTED);
        switch (draft.getStatus()) {
            case EXECUTED -> {
                audit(draft.getId(), conversationId, customerId, PaymentAuditAction.CONFIRM_REPLAYED, "NO_OP",
                        draft.getBeneficiaryName(), draft.getAmount(), "DRAFT_ALREADY_EXECUTED",
                        "Duplicate confirmation ignored; no additional debit.");
                BigDecimal balance = accounts.findById(draft.getFromAccountId()).map(BankAccount::getBalance).orElse(null);
                return new ConfirmationResult(toView(draft), true, balance,
                        "This transfer was already executed. No additional debit was made.");
            }
            case CANCELLED -> throw rejectConfirm(draft, conversationId, customerId, ErrorCode.DRAFT_CANCELLED,
                    "This transfer draft was cancelled and can no longer be confirmed.");
            case EXPIRED -> throw rejectConfirm(draft, conversationId, customerId, ErrorCode.DRAFT_EXPIRED,
                    "This transfer draft has expired. Please ask the assistant to prepare a new one.");
            case FAILED -> throw rejectConfirm(draft, conversationId, customerId, ErrorCode.DRAFT_FAILED,
                    "This transfer draft already failed (" + draft.getFailureCode() + ") and cannot be retried. "
                            + "Please create a new draft.");
            case PENDING_CONFIRMATION -> {
                // continue below
            }
        }
        if (draft.isExpiredAt(now)) {
            draft.markExpired(now);
            audit(draft.getId(), conversationId, customerId, PaymentAuditAction.EXPIRED, "EXPIRED",
                    draft.getBeneficiaryName(), draft.getAmount(), "DRAFT_EXPIRED", "Expired at confirmation time.");
            throw new BankingException(ErrorCode.DRAFT_EXPIRED,
                    "This transfer draft has expired. Please ask the assistant to prepare a new one.");
        }
        BankAccount account = accounts.findByIdForUpdate(draft.getFromAccountId())
                .filter(a -> a.getCustomerId().equals(customerId) && a.isActive())
                .orElseThrow(() -> failDraft(draft, now, conversationId, customerId, ErrorCode.ACCOUNT_NOT_AVAILABLE,
                        "The source account is not available."));
        if (beneficiaries.findByIdAndCustomerId(draft.getBeneficiaryId(), customerId).isEmpty()) {
            throw failDraft(draft, now, conversationId, customerId, ErrorCode.BENEFICIARY_NOT_REGISTERED,
                    "The beneficiary is no longer registered.");
        }
        if (draft.getAmount().compareTo(properties.payments().singleTransferLimit()) > 0) {
            throw failDraft(draft, now, conversationId, customerId, ErrorCode.AMOUNT_EXCEEDS_LIMIT,
                    "The amount exceeds the single transfer limit.");
        }
        if (account.getBalance().compareTo(draft.getAmount()) < 0) {
            throw failDraft(draft, now, conversationId, customerId, ErrorCode.INSUFFICIENT_FUNDS,
                    "Insufficient funds: available " + account.getBalance().toPlainString() + " "
                            + account.getCurrency() + ", required " + draft.getAmount().toPlainString() + ".");
        }
        account.debit(draft.getAmount());
        BankTransaction txn = transactions.save(new BankTransaction(account.getId(), now, TransactionDirection.DEBIT,
                TransactionService.TRANSFER_CATEGORY, "转账-" + draft.getBeneficiaryName(),
                draft.getMemo() == null ? "Simulated transfer" : draft.getMemo(), draft.getAmount(),
                draft.getCurrency(), account.getBalance(), draft.getId()));
        draft.markExecuted(now, txn.getId());
        audit(draft.getId(), conversationId, customerId, PaymentAuditAction.CONFIRM_EXECUTED, "EXECUTED",
                draft.getBeneficiaryName(), draft.getAmount(), null,
                "Debited " + account.getId() + "; transaction " + txn.getId() + ".");
        log.info("Transfer executed draft={} txn={} amount={}", draft.getId(), txn.getId(), draft.getAmount());
        return new ConfirmationResult(toView(draft), false, account.getBalance(),
                "Transfer executed (simulated). " + draft.getAmount().toPlainString() + " " + draft.getCurrency()
                        + " sent to " + draft.getBeneficiaryName() + ".");
    }

    @Transactional(noRollbackFor = BankingException.class)
    public TransferDraftView cancel(String draftId, String conversationId, String customerId, String reason) {
        LocalDateTime now = LocalDateTime.now(clock);
        TransferDraft draft = lockOwnedDraft(draftId, conversationId, customerId, PaymentAuditAction.CANCEL_REJECTED);
        if (draft.getStatus() == TransferStatus.EXECUTED) {
            audit(draft.getId(), conversationId, customerId, PaymentAuditAction.CANCEL_REJECTED, "REJECTED",
                    draft.getBeneficiaryName(), draft.getAmount(), ErrorCode.DRAFT_ALREADY_EXECUTED.name(),
                    "Executed transfers cannot be cancelled.");
            throw new BankingException(ErrorCode.DRAFT_ALREADY_EXECUTED,
                    "This transfer has already been executed and cannot be cancelled.");
        }
        if (draft.getStatus() != TransferStatus.PENDING_CONFIRMATION) {
            return toView(draft);
        }
        if (draft.isExpiredAt(now)) {
            draft.markExpired(now);
            audit(draft.getId(), conversationId, customerId, PaymentAuditAction.EXPIRED, "EXPIRED",
                    draft.getBeneficiaryName(), draft.getAmount(), "DRAFT_EXPIRED", "Expired before cancellation.");
            return toView(draft);
        }
        draft.markCancelled(now, "USER_CANCELLED", reason == null ? "Cancelled by the user." : reason);
        audit(draft.getId(), conversationId, customerId, PaymentAuditAction.CANCELLED, "CANCELLED",
                draft.getBeneficiaryName(), draft.getAmount(), "USER_CANCELLED", draft.getFailureReason());
        log.info("Transfer draft cancelled id={} conversation={}", draft.getId(), conversationId);
        return toView(draft);
    }

    /** Cancels the latest pending draft of the conversation, used by the Payments agent tool. */
    @Transactional(noRollbackFor = BankingException.class)
    public Optional<TransferDraftView> cancelLatestPending(String conversationId, String customerId, String reason) {
        expireStale(conversationId, customerId);
        return drafts.findByConversationIdAndStatusOrderByCreatedAtDesc(conversationId,
                        TransferStatus.PENDING_CONFIRMATION).stream()
                .findFirst()
                .map(d -> cancel(d.getId(), conversationId, customerId, reason));
    }

    @Transactional
    public List<TransferDraftView> recentDrafts(String conversationId, String customerId) {
        expireStale(conversationId, customerId);
        return drafts.findTop10ByConversationIdOrderByCreatedAtDesc(conversationId).stream()
                .filter(d -> d.getCustomerId().equals(customerId))
                .map(this::toView).toList();
    }

    @Transactional(readOnly = true)
    public List<AuditView> recentAudit(String conversationId) {
        return audits.findTop10ByConversationIdOrderByCreatedAtDescIdDesc(conversationId).stream()
                .map(a -> new AuditView(a.getAction().name(), a.getOutcome(), a.getDraftId(), a.getBeneficiaryName(),
                        a.getAmount(), a.getCode(), a.getDetail(), a.getCreatedAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public Optional<TransferDraftView> findDraft(String draftId) {
        return drafts.findById(draftId).map(this::toView);
    }

    /**
     * Read-only lookup of a draft owned by this conversation/customer (no audit, no lock). Used to build the Agent
     * Control input for the confirmation step; the authoritative checks still happen inside {@link #confirm}.
     */
    @Transactional(readOnly = true)
    public Optional<TransferDraftView> findOwnedDraft(String draftId, String conversationId, String customerId) {
        return drafts.findById(draftId)
                .filter(d -> d.getConversationId().equals(conversationId) && d.getCustomerId().equals(customerId))
                .map(this::toView);
    }

    /**
     * Agent Control: a pending draft whose creation output was blocked by Agent Control (post deny / steer / fail-closed)
     * is withdrawn - cancelled with code CONTROL_WITHDRAWN and an audit record. Nothing is deleted; no money moves.
     */
    @Transactional(noRollbackFor = BankingException.class)
    public Optional<TransferDraftView> withdrawByControl(String draftId, String conversationId, String customerId,
                                                         String reason) {
        LocalDateTime now = LocalDateTime.now(clock);
        TransferDraft draft = lockOwnedDraft(draftId, conversationId, customerId, PaymentAuditAction.CANCEL_REJECTED);
        if (draft.getStatus() != TransferStatus.PENDING_CONFIRMATION) {
            return Optional.of(toView(draft));
        }
        String detail = shorten(reason, 290);
        draft.markCancelled(now, "CONTROL_WITHDRAWN", detail);
        audit(draft.getId(), conversationId, customerId, PaymentAuditAction.CANCELLED, "CANCELLED",
                draft.getBeneficiaryName(), draft.getAmount(), "CONTROL_WITHDRAWN", detail);
        log.info("Transfer draft withdrawn by Agent Control id={} conversation={}", draft.getId(), conversationId);
        return Optional.of(toView(draft));
    }

    /** Agent Control: audits a confirmation that Agent Control blocked before {@link #confirm} ran (no debit happened). */
    @Transactional
    public void auditControlRejectedConfirmation(String draftId, String conversationId, String customerId, String code,
                                                 String detail) {
        TransferDraft draft = drafts.findById(draftId).orElse(null);
        audit(draftId, conversationId, customerId, PaymentAuditAction.CONFIRM_REJECTED, "REJECTED",
                draft == null ? null : draft.getBeneficiaryName(), draft == null ? null : draft.getAmount(),
                shorten(code, 190), shorten(detail, 490));
    }

    private static String shorten(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() > max ? text.substring(0, max) : text;
    }

    private void expireStale(String conversationId, String customerId) {
        LocalDateTime now = LocalDateTime.now(clock);
        for (TransferDraft d : drafts.findByConversationIdAndStatusOrderByCreatedAtDesc(conversationId,
                TransferStatus.PENDING_CONFIRMATION)) {
            if (d.isExpiredAt(now)) {
                d.markExpired(now);
                audit(d.getId(), conversationId, customerId, PaymentAuditAction.EXPIRED, "EXPIRED",
                        d.getBeneficiaryName(), d.getAmount(), "DRAFT_EXPIRED", "Expired without confirmation.");
            }
        }
    }

    private TransferDraft lockOwnedDraft(String draftId, String conversationId, String customerId,
                                         PaymentAuditAction rejectAction) {
        TransferDraft draft = drafts.findByIdForUpdate(draftId)
                .orElseThrow(() -> new BankingException(ErrorCode.DRAFT_NOT_FOUND, "Transfer draft not found."));
        if (!draft.getConversationId().equals(conversationId) || !draft.getCustomerId().equals(customerId)) {
            audit(draft.getId(), conversationId, customerId, rejectAction, "REJECTED", null, null,
                    ErrorCode.DRAFT_CONVERSATION_MISMATCH.name(), "Draft belongs to a different conversation.");
            log.warn("Rejected cross-conversation access to draft={} from conversation={}", draftId, conversationId);
            throw new BankingException(ErrorCode.DRAFT_CONVERSATION_MISMATCH,
                    "This transfer draft does not belong to the current conversation.");
        }
        return draft;
    }

    private BankingException rejectConfirm(TransferDraft draft, String conversationId, String customerId,
                                           ErrorCode code, String message) {
        audit(draft.getId(), conversationId, customerId, PaymentAuditAction.CONFIRM_REJECTED, "REJECTED",
                draft.getBeneficiaryName(), draft.getAmount(), code.name(), message);
        return new BankingException(code, message);
    }

    private BankingException failDraft(TransferDraft draft, LocalDateTime now, String conversationId,
                                       String customerId, ErrorCode code, String message) {
        draft.markFailed(now, code.name(), message);
        audit(draft.getId(), conversationId, customerId, PaymentAuditAction.CONFIRM_REJECTED, "FAILED",
                draft.getBeneficiaryName(), draft.getAmount(), code.name(), message);
        log.info("Transfer failed draft={} code={}", draft.getId(), code);
        return new BankingException(code, message);
    }

    private Checked check(String customerId, String beneficiaryRef, String amountText, String fromAccountRef) {
        List<TransferIssue> issues = new ArrayList<>();
        BigDecimal limit = properties.payments().singleTransferLimit();

        BankAccount account = null;
        try {
            account = accountService.resolveAccount(customerId, fromAccountRef);
            if (account.getAccountType() != AccountType.CHECKING || !account.isActive()) {
                issues.add(new TransferIssue(ErrorCode.ACCOUNT_NOT_AVAILABLE.name(),
                        "Transfers can only be made from the active checking account."));
                account = null;
            }
        } catch (BankingException e) {
            issues.add(new TransferIssue(e.getCode().name(), e.getMessage()));
        }

        Beneficiary beneficiary = resolveBeneficiary(customerId, beneficiaryRef).orElse(null);
        if (beneficiary == null) {
            String registered = beneficiaries.findByCustomerIdOrderByIdAsc(customerId).stream()
                    .map(Beneficiary::getName).collect(Collectors.joining(", "));
            issues.add(new TransferIssue(ErrorCode.BENEFICIARY_NOT_REGISTERED.name(),
                    "Beneficiary '" + (beneficiaryRef == null ? "" : beneficiaryRef)
                            + "' is not on the registered beneficiary list. Registered: " + registered + "."));
        }

        BigDecimal amount = AmountParser.parse(amountText).orElse(null);
        if (amount == null) {
            issues.add(new TransferIssue(ErrorCode.INVALID_AMOUNT.name(),
                    "Amount '" + amountText + "' is not a valid number."));
        } else if (amount.signum() <= 0) {
            issues.add(new TransferIssue(ErrorCode.INVALID_AMOUNT.name(), "Amount must be greater than zero."));
        } else if (amount.scale() > 2) {
            issues.add(new TransferIssue(ErrorCode.INVALID_AMOUNT.name(), "Amount can have at most 2 decimal places."));
        } else {
            amount = amount.setScale(2, RoundingMode.UNNECESSARY);
            if (amount.compareTo(limit) > 0) {
                issues.add(new TransferIssue(ErrorCode.AMOUNT_EXCEEDS_LIMIT.name(),
                        "Amount " + amount.toPlainString() + " exceeds the single transfer limit of "
                                + limit.toPlainString() + " CNY."));
            }
            if (account != null && account.getBalance().compareTo(amount) < 0) {
                issues.add(new TransferIssue(ErrorCode.INSUFFICIENT_FUNDS.name(),
                        "Insufficient funds: available " + account.getBalance().toPlainString() + " CNY."));
            }
        }
        TransferValidationView view = new TransferValidationView(issues.isEmpty(), List.copyOf(issues),
                beneficiary == null ? null : beneficiary.getId(), beneficiary == null ? null : beneficiary.getName(),
                amount, "CNY", account == null ? null : account.getId(),
                account == null ? null : account.getBalance(), limit);
        return new Checked(view, account, beneficiary, amount);
    }

    Optional<Beneficiary> resolveBeneficiary(String customerId, String ref) {
        if (ref == null || ref.isBlank()) {
            return Optional.empty();
        }
        String r = ref.trim();
        List<Beneficiary> owned = beneficiaries.findByCustomerIdOrderByIdAsc(customerId);
        Optional<Beneficiary> exact = owned.stream()
                .filter(b -> b.getId().equalsIgnoreCase(r) || b.getName().equals(r)).findFirst();
        if (exact.isPresent()) {
            return exact;
        }
        List<Beneficiary> contained = owned.stream()
                .filter(b -> r.toLowerCase(Locale.ROOT).contains(b.getName().toLowerCase(Locale.ROOT))).toList();
        return contained.size() == 1 ? Optional.of(contained.get(0)) : Optional.empty();
    }

    private void audit(String draftId, String conversationId, String customerId, PaymentAuditAction action,
                       String outcome, String beneficiaryName, BigDecimal amount, String code, String detail) {
        audits.save(new PaymentAudit(draftId, conversationId, customerId, action, outcome, beneficiaryName, amount,
                code, detail, LocalDateTime.now(clock)));
    }

    private static String displayName(Checked checked, String beneficiaryRef) {
        if (checked.beneficiary() != null) {
            return checked.beneficiary().getName();
        }
        if (beneficiaryRef == null) {
            return null;
        }
        return beneficiaryRef.length() > 100 ? beneficiaryRef.substring(0, 100) : beneficiaryRef;
    }

    private static String codes(List<TransferIssue> issues) {
        return issues.stream().map(TransferIssue::code).collect(Collectors.joining(","));
    }

    private static String messages(List<TransferIssue> issues) {
        return issues.stream().map(TransferIssue::message).collect(Collectors.joining(" | "));
    }

    public TransferDraftView toView(TransferDraft d) {
        String fromMasked = accounts.findById(d.getFromAccountId()).map(BankAccount::getMaskedNumber).orElse(null);
        String benMasked = beneficiaries.findById(d.getBeneficiaryId()).map(Beneficiary::getMaskedAccount).orElse(null);
        return new TransferDraftView(d.getId(), d.getStatus().name(), d.getFromAccountId(), fromMasked,
                d.getBeneficiaryId(), d.getBeneficiaryName(), benMasked, d.getAmount(), d.getCurrency(), d.getMemo(),
                d.getCreatedAt(), d.getExpiresAt(), d.getResolvedAt(), d.getFailureCode(), d.getFailureReason(),
                d.getTransactionId());
    }
}
