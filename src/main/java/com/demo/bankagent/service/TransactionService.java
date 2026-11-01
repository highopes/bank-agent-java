package com.demo.bankagent.service;

import com.demo.bankagent.domain.BankAccount;
import com.demo.bankagent.domain.BankTransaction;
import com.demo.bankagent.domain.TransactionDirection;
import com.demo.bankagent.repository.BankTransactionRepository;
import com.demo.bankagent.service.Views.CategoryTotal;
import com.demo.bankagent.service.Views.SpendingSummaryView;
import com.demo.bankagent.service.Views.TransactionView;
import jakarta.persistence.criteria.Predicate;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transaction queries and spending aggregation. All arithmetic is done here with BigDecimal;
 * the LLM only explains the numbers it receives.
 */
@Service
@Transactional(readOnly = true)
public class TransactionService {

    public static final String TRANSFER_CATEGORY = "转账";
    public static final int MAX_LIMIT = 50;

    private final BankTransactionRepository transactions;
    private final AccountService accountService;
    private final Clock clock;

    public TransactionService(BankTransactionRepository transactions, AccountService accountService, Clock clock) {
        this.transactions = transactions;
        this.accountService = accountService;
        this.clock = clock;
    }

    public record SearchCriteria(String accountRef, LocalDate fromDate, LocalDate toDate, String direction,
                                 String category, String merchantKeyword, BigDecimal minAmount,
                                 BigDecimal maxAmount, Integer limit) {
    }

    public List<TransactionView> listRecent(String customerId, String accountRef, Integer limit) {
        List<String> accountIds = accountIds(customerId, accountRef);
        return transactions.findRecent(accountIds, PageRequest.of(0, clampLimit(limit, 5)))
                .stream().map(TransactionService::toView).toList();
    }

    public List<TransactionView> search(String customerId, SearchCriteria criteria) {
        List<String> accountIds = accountIds(customerId, criteria.accountRef());
        if (criteria.fromDate() != null && criteria.toDate() != null && criteria.toDate().isBefore(criteria.fromDate())) {
            throw new BankingException(ErrorCode.VALIDATION_ERROR, "toDate must not be before fromDate.");
        }
        TransactionDirection direction = parseDirection(criteria.direction());
        Specification<BankTransaction> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(root.get("accountId").in(accountIds));
            if (criteria.fromDate() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("bookedAt"), criteria.fromDate().atStartOfDay()));
            }
            if (criteria.toDate() != null) {
                predicates.add(cb.lessThan(root.get("bookedAt"), criteria.toDate().plusDays(1).atStartOfDay()));
            }
            if (direction != null) {
                predicates.add(cb.equal(root.get("direction"), direction));
            }
            if (criteria.category() != null && !criteria.category().isBlank()) {
                predicates.add(cb.like(root.get("category"), "%" + escapeLike(criteria.category().trim()) + "%", '\\'));
            }
            if (criteria.merchantKeyword() != null && !criteria.merchantKeyword().isBlank()) {
                String kw = "%" + escapeLike(criteria.merchantKeyword().trim().toLowerCase(Locale.ROOT)) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("merchant")), kw, '\\'),
                        cb.like(cb.lower(root.get("description")), kw, '\\')));
            }
            if (criteria.minAmount() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("amount"), criteria.minAmount()));
            }
            if (criteria.maxAmount() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("amount"), criteria.maxAmount()));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        Sort sort = Sort.by(Sort.Order.desc("bookedAt"), Sort.Order.desc("id"));
        return transactions.findAll(spec, PageRequest.of(0, clampLimit(criteria.limit(), 20), sort))
                .stream().map(TransactionService::toView).toList();
    }

    public SpendingSummaryView summarizeSpending(String customerId, Period period, LocalDate customFrom,
                                                 LocalDate customTo, String accountRef, boolean includeTransfers) {
        LocalDate today = LocalDate.now(clock);
        Period.Range range = period.resolve(today, customFrom, customTo);
        List<String> accountIds = accountIds(customerId, accountRef);
        List<BankTransaction> rows = transactions.findInRange(accountIds, range.from().atStartOfDay(),
                range.to().plusDays(1).atStartOfDay());

        BigDecimal totalSpending = BigDecimal.ZERO;
        BigDecimal totalIncome = BigDecimal.ZERO;
        BigDecimal excludedTransfers = BigDecimal.ZERO;
        int spendingCount = 0;
        int incomeCount = 0;
        Map<String, BigDecimal> byCategory = new LinkedHashMap<>();
        Map<String, Integer> countByCategory = new LinkedHashMap<>();
        for (BankTransaction t : rows) {
            if (t.getDirection() == TransactionDirection.CREDIT) {
                totalIncome = totalIncome.add(t.getAmount());
                incomeCount++;
                continue;
            }
            if (!includeTransfers && TRANSFER_CATEGORY.equals(t.getCategory())) {
                excludedTransfers = excludedTransfers.add(t.getAmount());
                continue;
            }
            totalSpending = totalSpending.add(t.getAmount());
            spendingCount++;
            byCategory.merge(t.getCategory(), t.getAmount(), BigDecimal::add);
            countByCategory.merge(t.getCategory(), 1, Integer::sum);
        }
        BigDecimal total = totalSpending;
        List<CategoryTotal> categories = byCategory.entrySet().stream()
                .sorted(Map.Entry.<String, BigDecimal>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry.comparingByKey()))
                .map(e -> new CategoryTotal(e.getKey(), e.getValue(), countByCategory.get(e.getKey()),
                        total.signum() == 0 ? BigDecimal.ZERO
                                : e.getValue().multiply(BigDecimal.valueOf(100)).divide(total, 1, RoundingMode.HALF_UP)))
                .toList();
        String top = categories.isEmpty() ? null : categories.get(0).category();
        BigDecimal topAmount = categories.isEmpty() ? BigDecimal.ZERO : categories.get(0).amount();
        String note = includeTransfers
                ? "Spending includes outgoing transfers."
                : "Spending excludes outgoing transfers to beneficiaries (category 转账). Income is reported separately.";
        return new SpendingSummaryView(period.name(), range.from(), range.to(), "CNY", totalSpending, spendingCount,
                totalIncome, incomeCount, categories, top, topAmount, includeTransfers, excludedTransfers, note);
    }

    private List<String> accountIds(String customerId, String accountRef) {
        return accountService.resolveAccounts(customerId, accountRef).stream().map(BankAccount::getId).toList();
    }

    static TransactionDirection parseDirection(String raw) {
        if (raw == null || raw.isBlank() || "ALL".equalsIgnoreCase(raw.trim())) {
            return null;
        }
        String r = raw.trim().toUpperCase(Locale.ROOT);
        if (r.equals("DEBIT") || r.equals("OUT") || r.equals("EXPENSE") || raw.contains("支出")) {
            return TransactionDirection.DEBIT;
        }
        if (r.equals("CREDIT") || r.equals("IN") || r.equals("INCOME") || raw.contains("收入")) {
            return TransactionDirection.CREDIT;
        }
        throw new BankingException(ErrorCode.VALIDATION_ERROR,
                "Unsupported direction '" + raw + "'. Use DEBIT, CREDIT or ALL.");
    }

    private static int clampLimit(Integer limit, int fallback) {
        if (limit == null || limit <= 0) {
            return fallback;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    static TransactionView toView(BankTransaction t) {
        return new TransactionView(t.getId(), t.getAccountId(), t.getBookedAt(), t.getDirection().name(),
                t.getCategory(), t.getMerchant(), t.getDescription(), t.getAmount(), t.getCurrency(),
                t.getBalanceAfter());
    }
}
