package com.demo.bankagent.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Read-only projections returned by services. They contain only masked, fictional data and are
 * safe to serialize to the LLM (as tool results) and to the browser.
 */
public final class Views {

    private Views() {
    }

    public record AccountView(String accountId, String type, String typeLabel, String name, String maskedNumber,
                              String currency, BigDecimal balance, String status) {
    }

    public record AccountSummaryView(String customerName, String customerSegment, List<AccountView> accounts,
                                     BigDecimal totalBalance, String currency, int beneficiaryCount,
                                     String note) {
    }

    public record BeneficiaryView(String beneficiaryId, String name, String bankName, String maskedAccount,
                                  String relationship) {
    }

    public record PaymentMethodView(String accountId, String name, String maskedNumber, boolean transferEnabled,
                                    String description) {
    }

    public record TransactionView(Long transactionId, String accountId, LocalDateTime bookedAt, String direction,
                                  String category, String merchant, String description, BigDecimal amount,
                                  String currency, BigDecimal balanceAfter) {
    }

    public record CategoryTotal(String category, BigDecimal amount, int count, BigDecimal sharePercent) {
    }

    public record SpendingSummaryView(String period, LocalDate fromDate, LocalDate toDate, String currency,
                                      BigDecimal totalSpending, int spendingCount, BigDecimal totalIncome,
                                      int incomeCount, List<CategoryTotal> spendingByCategory,
                                      String topCategory, BigDecimal topCategoryAmount,
                                      boolean transfersIncluded, BigDecimal excludedTransferAmount,
                                      String note) {
    }

    public record TransferDraftView(String draftId, String status, String fromAccountId, String fromAccountMasked,
                                    String beneficiaryId, String beneficiaryName, String beneficiaryMaskedAccount,
                                    BigDecimal amount, String currency, String memo, LocalDateTime createdAt,
                                    LocalDateTime expiresAt, LocalDateTime resolvedAt, String failureCode,
                                    String failureReason, Long transactionId) {
    }

    public record TransferIssue(String code, String message) {
    }

    public record TransferValidationView(boolean valid, List<TransferIssue> issues, String beneficiaryId,
                                         String beneficiaryName, BigDecimal amount, String currency,
                                         String fromAccountId, BigDecimal availableBalance,
                                         BigDecimal singleTransferLimit) {
    }

    public record AuditView(String action, String outcome, String draftId, String beneficiaryName,
                            BigDecimal amount, String code, String detail, LocalDateTime at) {
    }

    public record ConfirmationResult(TransferDraftView draft, boolean replayed, BigDecimal balanceAfter,
                                     String message) {
    }
}
