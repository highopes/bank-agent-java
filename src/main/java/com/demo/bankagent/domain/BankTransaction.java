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

@Entity
@Table(name = "bank_transaction")
public class BankTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private String accountId;

    @Column(name = "booked_at", nullable = false)
    private LocalDateTime bookedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private TransactionDirection direction;

    @Column(nullable = false, length = 30)
    private String category;

    @Column(nullable = false)
    private String merchant;

    private String description;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "balance_after", precision = 19, scale = 2)
    private BigDecimal balanceAfter;

    private String reference;

    protected BankTransaction() {
    }

    public BankTransaction(String accountId, LocalDateTime bookedAt, TransactionDirection direction, String category,
                           String merchant, String description, BigDecimal amount, String currency,
                           BigDecimal balanceAfter, String reference) {
        this.accountId = accountId;
        this.bookedAt = bookedAt;
        this.direction = direction;
        this.category = category;
        this.merchant = merchant;
        this.description = description;
        this.amount = amount;
        this.currency = currency;
        this.balanceAfter = balanceAfter;
        this.reference = reference;
    }

    public Long getId() {
        return id;
    }

    public String getAccountId() {
        return accountId;
    }

    public LocalDateTime getBookedAt() {
        return bookedAt;
    }

    public TransactionDirection getDirection() {
        return direction;
    }

    public String getCategory() {
        return category;
    }

    public String getMerchant() {
        return merchant;
    }

    public String getDescription() {
        return description;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getBalanceAfter() {
        return balanceAfter;
    }

    public String getReference() {
        return reference;
    }
}
