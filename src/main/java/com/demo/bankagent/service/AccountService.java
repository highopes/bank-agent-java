package com.demo.bankagent.service;

import com.demo.bankagent.domain.AccountType;
import com.demo.bankagent.domain.BankAccount;
import com.demo.bankagent.domain.Beneficiary;
import com.demo.bankagent.domain.DemoCustomer;
import com.demo.bankagent.repository.BankAccountRepository;
import com.demo.bankagent.repository.BeneficiaryRepository;
import com.demo.bankagent.repository.DemoCustomerRepository;
import com.demo.bankagent.service.Views.AccountSummaryView;
import com.demo.bankagent.service.Views.AccountView;
import com.demo.bankagent.service.Views.BeneficiaryView;
import com.demo.bankagent.service.Views.PaymentMethodView;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Account and beneficiary queries. Every method is scoped to the customer bound to the conversation. */
@Service
@Transactional(readOnly = true)
public class AccountService {

    private final DemoCustomerRepository customers;
    private final BankAccountRepository accounts;
    private final BeneficiaryRepository beneficiaries;

    public AccountService(DemoCustomerRepository customers, BankAccountRepository accounts,
                          BeneficiaryRepository beneficiaries) {
        this.customers = customers;
        this.accounts = accounts;
        this.beneficiaries = beneficiaries;
    }

    public List<AccountView> listAccounts(String customerId) {
        return accounts.findByCustomerIdOrderByIdAsc(customerId).stream().map(AccountService::toView).toList();
    }

    /**
     * Resolves a free-form account reference (id, type, or Chinese label) for the given customer.
     * A blank reference means the primary checking account.
     */
    public BankAccount resolveAccount(String customerId, String accountRef) {
        List<BankAccount> owned = accounts.findByCustomerIdOrderByIdAsc(customerId);
        if (owned.isEmpty()) {
            throw new BankingException(ErrorCode.ACCOUNT_NOT_AVAILABLE, "No demo account found for this customer.");
        }
        AccountType wanted = parseAccountType(accountRef);
        if (accountRef == null || accountRef.isBlank()) {
            wanted = AccountType.CHECKING;
        }
        for (BankAccount account : owned) {
            if (accountRef != null && account.getId().equalsIgnoreCase(accountRef.trim())) {
                return account;
            }
        }
        if (wanted != null) {
            AccountType type = wanted;
            return owned.stream().filter(a -> a.getAccountType() == type).findFirst()
                    .orElseThrow(() -> new BankingException(ErrorCode.ACCOUNT_NOT_AVAILABLE,
                            "The customer has no " + type.label() + "."));
        }
        throw new BankingException(ErrorCode.ACCOUNT_NOT_AVAILABLE,
                "Unknown account '" + accountRef + "'. Available accounts: "
                        + owned.stream().map(a -> a.getId() + " (" + a.getAccountType().label() + ")").toList());
    }

    public List<BankAccount> resolveAccounts(String customerId, String accountRef) {
        if (accountRef == null || accountRef.isBlank() || "ALL".equalsIgnoreCase(accountRef.trim())
                || accountRef.contains("全部") || accountRef.contains("所有")) {
            return accounts.findByCustomerIdOrderByIdAsc(customerId);
        }
        return List.of(resolveAccount(customerId, accountRef));
    }

    public AccountView getBalance(String customerId, String accountRef) {
        return toView(resolveAccount(customerId, accountRef));
    }

    public AccountSummaryView getSummary(String customerId) {
        DemoCustomer customer = customers.findById(customerId)
                .orElseThrow(() -> new BankingException(ErrorCode.ACCOUNT_NOT_AVAILABLE, "Demo customer not found."));
        List<AccountView> views = listAccounts(customerId);
        BigDecimal total = views.stream().map(AccountView::balance).reduce(BigDecimal.ZERO, BigDecimal::add);
        int beneficiaryCount = beneficiaries.findByCustomerIdOrderByIdAsc(customerId).size();
        return new AccountSummaryView(customer.getDisplayName(), customer.getSegment(), views, total, "CNY",
                beneficiaryCount, "Simulated demo data. Account numbers are masked.");
    }

    public List<BeneficiaryView> listBeneficiaries(String customerId) {
        return beneficiaries.findByCustomerIdOrderByIdAsc(customerId).stream().map(AccountService::toView).toList();
    }

    public List<PaymentMethodView> listPaymentMethods(String customerId) {
        return accounts.findByCustomerIdOrderByIdAsc(customerId).stream()
                .map(a -> new PaymentMethodView(a.getId(), a.getAccountName(), a.getMaskedNumber(),
                        a.getAccountType() == AccountType.CHECKING && a.isActive(),
                        a.getAccountType() == AccountType.CHECKING
                                ? "Can be used as the source account for transfers to registered beneficiaries."
                                : "Savings account: transfers out are not supported in this demo."))
                .toList();
    }

    public String customerDisplayName(String customerId) {
        return customers.findById(customerId).map(DemoCustomer::getDisplayName).orElse(customerId);
    }

    static AccountType parseAccountType(String ref) {
        if (ref == null || ref.isBlank()) {
            return null;
        }
        String r = ref.trim().toLowerCase(Locale.ROOT);
        if (r.contains("checking") || r.contains("current") || r.contains("活期") || r.contains("chk")) {
            return AccountType.CHECKING;
        }
        if (r.contains("saving") || r.contains("储蓄") || r.contains("定期") || r.contains("sav")) {
            return AccountType.SAVINGS;
        }
        return null;
    }

    static AccountView toView(BankAccount a) {
        return new AccountView(a.getId(), a.getAccountType().name(), a.getAccountType().label(), a.getAccountName(),
                a.getMaskedNumber(), a.getCurrency(), a.getBalance(), a.getStatus());
    }

    static BeneficiaryView toView(Beneficiary b) {
        return new BeneficiaryView(b.getId(), b.getName(), b.getBankName(), b.getMaskedAccount(), b.getRelationship());
    }
}
