package com.demo.bankagent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "bank_account")
public class BankAccount {

    @Id
    private String id;

    @Column(name = "customer_id", nullable = false)
    private String customerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, length = 20)
    private AccountType accountType;

    @Column(name = "account_name", nullable = false)
    private String accountName;

    @Column(name = "masked_number", nullable = false)
    private String maskedNumber;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balance;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "opened_on", nullable = false)
    private LocalDate openedOn;

    @Version
    private long version;

    protected BankAccount() {
    }

    public BankAccount(String id, String customerId, AccountType accountType, String accountName,
                       String maskedNumber, String currency, BigDecimal balance, LocalDate openedOn) {
        this.id = id;
        this.customerId = customerId;
        this.accountType = accountType;
        this.accountName = accountName;
        this.maskedNumber = maskedNumber;
        this.currency = currency;
        this.balance = balance;
        this.status = "ACTIVE";
        this.openedOn = openedOn;
    }

    public void debit(BigDecimal amount) {
        this.balance = this.balance.subtract(amount);
    }

    public String getId() {
        return id;
    }

    public String getCustomerId() {
        return customerId;
    }

    public AccountType getAccountType() {
        return accountType;
    }

    public String getAccountName() {
        return accountName;
    }

    public String getMaskedNumber() {
        return maskedNumber;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public String getStatus() {
        return status;
    }

    public LocalDate getOpenedOn() {
        return openedOn;
    }

    public boolean isActive() {
        return "ACTIVE".equals(status);
    }
}
