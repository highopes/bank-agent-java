package com.demo.bankagent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Append-only audit trail of every payment decision (accepted or rejected). */
@Entity
@Table(name = "payment_audit")
public class PaymentAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "draft_id")
    private String draftId;

    @Column(name = "conversation_id")
    private String conversationId;

    @Column(name = "customer_id", nullable = false)
    private String customerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PaymentAuditAction action;

    @Column(nullable = false, length = 20)
    private String outcome;

    @Column(name = "beneficiary_name")
    private String beneficiaryName;

    @Column(precision = 19, scale = 2)
    private BigDecimal amount;

    private String code;

    private String detail;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected PaymentAudit() {
    }

    public PaymentAudit(String draftId, String conversationId, String customerId, PaymentAuditAction action,
                        String outcome, String beneficiaryName, BigDecimal amount, String code, String detail,
                        LocalDateTime createdAt) {
        this.draftId = draftId;
        this.conversationId = conversationId;
        this.customerId = customerId;
        this.action = action;
        this.outcome = outcome;
        this.beneficiaryName = beneficiaryName;
        this.amount = amount;
        this.code = truncate(code, 200);
        this.detail = truncate(detail, 500);
        this.createdAt = createdAt;
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    public Long getId() {
        return id;
    }

    public String getDraftId() {
        return draftId;
    }

    public String getConversationId() {
        return conversationId;
    }

    public String getCustomerId() {
        return customerId;
    }

    public PaymentAuditAction getAction() {
        return action;
    }

    public String getOutcome() {
        return outcome;
    }

    public String getBeneficiaryName() {
        return beneficiaryName;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCode() {
        return code;
    }

    public String getDetail() {
        return detail;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
