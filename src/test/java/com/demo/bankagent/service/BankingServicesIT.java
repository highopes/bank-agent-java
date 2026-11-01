package com.demo.bankagent.service;

import com.demo.bankagent.repository.BankTransactionRepository;
import com.demo.bankagent.service.Views.AccountView;
import com.demo.bankagent.service.Views.SpendingSummaryView;
import com.demo.bankagent.service.Views.TransactionView;
import com.demo.bankagent.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BankingServicesIT extends IntegrationTestBase {

    @Autowired
    AccountService accountService;

    @Autowired
    TransactionService transactionService;

    @Autowired
    BankTransactionRepository transactions;

    @Test
    void seededAccountsHaveExpectedMaskedBalances() {
        List<AccountView> accounts = accountService.listAccounts(customerId());
        assertThat(accounts).extracting(AccountView::type).containsExactly("CHECKING", "SAVINGS");
        assertThat(accounts).allSatisfy(a -> assertThat(a.maskedNumber()).startsWith("DEMO-****"));
        assertThat(accountService.getBalance(customerId(), null).balance()).isEqualByComparingTo("52368.50");
        assertThat(accountService.getBalance(customerId(), "SAVINGS").balance()).isEqualByComparingTo("120000.00");
        assertThat(accountService.getSummary(customerId()).totalBalance()).isEqualByComparingTo("172368.50");
        assertThat(accountService.listBeneficiaries(customerId())).extracting(Views.BeneficiaryView::name)
                .containsExactly("张三", "李四", "王五");
    }

    @Test
    void unknownAccountIsAnExplainableBusinessError() {
        assertThatThrownBy(() -> accountService.getBalance(customerId(), "CREDIT-CARD"))
                .isInstanceOfSatisfying(BankingException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.ACCOUNT_NOT_AVAILABLE));
    }

    @Test
    void recentTransactionsAreNewestFirstAndNeverInTheFuture() {
        List<TransactionView> recent = transactionService.listRecent(customerId(), null, 5);
        assertThat(recent).hasSize(5);
        assertThat(recent).isSortedAccordingTo(Comparator.comparing(TransactionView::bookedAt).reversed());
        assertThat(recent).allSatisfy(t -> assertThat(t.bookedAt()).isBeforeOrEqualTo(FIXED_NOW));
    }

    @Test
    void lastMonthSpendingExcludesTransfersAndFindsTopCategory() {
        SpendingSummaryView summary = transactionService.summarizeSpending(customerId(), Period.LAST_MONTH,
                null, null, null, false);
        assertThat(summary.fromDate()).isEqualTo(LocalDate.of(2026, 2, 1));
        assertThat(summary.toDate()).isEqualTo(LocalDate.of(2026, 2, 28));
        assertThat(summary.totalSpending()).isEqualByComparingTo("10689.00");
        assertThat(summary.topCategory()).isEqualTo("购物");
        assertThat(summary.topCategoryAmount()).isEqualByComparingTo("4878.00");
        assertThat(summary.excludedTransferAmount()).isEqualByComparingTo("1000.00");
        assertThat(summary.totalIncome()).isEqualByComparingTo("18319.00");

        SpendingSummaryView withTransfers = transactionService.summarizeSpending(customerId(), Period.LAST_MONTH,
                null, null, "CHECKING", true);
        assertThat(withTransfers.totalSpending()).isEqualByComparingTo("11689.00");
        assertThat(withTransfers.totalIncome()).isEqualByComparingTo("18199.00");
    }

    @Test
    void seedingEarlyOnTheFirstDayOfAMonthKeepsTotalsAndCreatesNoFutureTransactions() {
        LocalDateTime earlyMorning = LocalDateTime.of(2026, 11, 1, 0, 30);
        clock.set(earlyMorning);
        demoResetService.reset();

        assertThat(transactions.findAll()).allSatisfy(t -> assertThat(t.getBookedAt()).isBeforeOrEqualTo(earlyMorning));
        SpendingSummaryView october = transactionService.summarizeSpending(customerId(), Period.LAST_MONTH,
                null, null, null, false);
        assertThat(october.fromDate()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(october.totalSpending()).isEqualByComparingTo("10689.00");
        assertThat(october.topCategoryAmount()).isEqualByComparingTo("4878.00");
        assertThat(accountService.getBalance(customerId(), null).balance()).isEqualByComparingTo("52368.50");
    }

    @Test
    void resetIsIdempotentAndRestoresSeedData() {
        long before = transactions.count();
        demoResetService.reset();
        demoResetService.reset();
        assertThat(transactions.count()).isEqualTo(before).isGreaterThanOrEqualTo(20);
        assertThat(accountService.listBeneficiaries(customerId())).hasSize(3);
    }
}
