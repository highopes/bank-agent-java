package com.demo.bankagent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A transfer that has been prepared but not executed. Only an explicit user confirmation
 * through the REST API (never the LLM) can move it to EXECUTED.
 */
@Entity
@Table(name = "transfer_draft")
public class TransferDraft {

    @Id
    private String id;

    @Column(name = "conversation_id", nullable = false)
    private String conversationId;

    @Column(name = "customer_id", nullable = false)
    private String customerId;

    @Column(name = "from_account_id", nullable = false)
    private String fromAccountId;

    @Column(name = "beneficiary_id", nullable = false)
    private String beneficiaryId;

    @Column(name = "beneficiary_name", nullable = false)
    private String beneficiaryName;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    private String memo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private TransferStatus status;

    @Column(name = "failure_code")
    private String failureCode;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "transaction_id")
    private Long transactionId;

    @Version
    private long version;

    protected TransferDraft() {
    }

    public TransferDraft(String id, String conversationId, String customerId, String fromAccountId,
                         String beneficiaryId, String beneficiaryName, BigDecimal amount, String currency,
                         String memo, LocalDateTime createdAt, LocalDateTime expiresAt) {
        this.id = id;
        this.conversationId = conversationId;
        this.customerId = customerId;
        this.fromAccountId = fromAccountId;
        this.beneficiaryId = beneficiaryId;
        this.beneficiaryName = beneficiaryName;
        this.amount = amount;
        this.currency = currency;
        this.memo = memo;
        this.status = TransferStatus.PENDING_CONFIRMATION;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public boolean isExpiredAt(LocalDateTime now) {
        return status == TransferStatus.PENDING_CONFIRMATION && !now.isBefore(expiresAt);
    }

    public void markExecuted(LocalDateTime now, Long transactionId) {
        this.status = TransferStatus.EXECUTED;
        this.resolvedAt = now;
        this.transactionId = transactionId;
    }

    public void markCancelled(LocalDateTime now, String code, String reason) {
        this.status = TransferStatus.CANCELLED;
        this.resolvedAt = now;
        this.failureCode = code;
        this.failureReason = reason;
    }

    public void markExpired(LocalDateTime now) {
        this.status = TransferStatus.EXPIRED;
        this.resolvedAt = now;
        this.failureCode = "DRAFT_EXPIRED";
        this.failureReason = "The draft was not confirmed before it expired.";
    }

    public void markFailed(LocalDateTime now, String code, String reason) {
        this.status = TransferStatus.FAILED;
        this.resolvedAt = now;
        this.failureCode = code;
        this.failureReason = reason;
    }

    public String getId() {
        return id;
    }

    public String getConversationId() {
        return conversationId;
    }

    public String getCustomerId() {
        return customerId;
    }

    public String getFromAccountId() {
        return fromAccountId;
    }

    public String getBeneficiaryId() {
        return beneficiaryId;
    }

    public String getBeneficiaryName() {
        return beneficiaryName;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getMemo() {
        return memo;
    }

    public TransferStatus getStatus() {
        return status;
    }

    public String getFailureCode() {
        return failureCode;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public LocalDateTime getResolvedAt() {
        return resolvedAt;
    }

    public Long getTransactionId() {
        return transactionId;
    }
}
