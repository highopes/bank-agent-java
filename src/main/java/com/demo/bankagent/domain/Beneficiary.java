package com.demo.bankagent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "beneficiary")
public class Beneficiary {

    @Id
    private String id;

    @Column(name = "customer_id", nullable = false)
    private String customerId;

    @Column(nullable = false)
    private String name;

    @Column(name = "bank_name", nullable = false)
    private String bankName;

    @Column(name = "masked_account", nullable = false)
    private String maskedAccount;

    private String relationship;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected Beneficiary() {
    }

    public Beneficiary(String id, String customerId, String name, String bankName, String maskedAccount,
                       String relationship, LocalDateTime createdAt) {
        this.id = id;
        this.customerId = customerId;
        this.name = name;
        this.bankName = bankName;
        this.maskedAccount = maskedAccount;
        this.relationship = relationship;
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public String getCustomerId() {
        return customerId;
    }

    public String getName() {
        return name;
    }

    public String getBankName() {
        return bankName;
    }

    public String getMaskedAccount() {
        return maskedAccount;
    }

    public String getRelationship() {
        return relationship;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
