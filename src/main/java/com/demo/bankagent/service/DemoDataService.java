package com.demo.bankagent.service;

import com.demo.bankagent.config.BankAgentProperties;
import com.demo.bankagent.domain.AccountType;
import com.demo.bankagent.domain.BankAccount;
import com.demo.bankagent.domain.BankTransaction;
import com.demo.bankagent.domain.Beneficiary;
import com.demo.bankagent.domain.DemoCustomer;
import com.demo.bankagent.domain.TransactionDirection;
import com.demo.bankagent.repository.BankAccountRepository;
import com.demo.bankagent.repository.BankTransactionRepository;
import com.demo.bankagent.repository.BeneficiaryRepository;
import com.demo.bankagent.repository.DemoCustomerRepository;
import com.demo.bankagent.repository.PaymentAuditRepository;
import com.demo.bankagent.repository.TransferDraftRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates a deterministic, clearly fictional data set. Transaction dates are relative to the demo
 * clock at seed time: fixed days (1-28) of the previous calendar month and days of the current month
 * capped at "today", so "last month" and "this month" questions always have data.
 */
@Service
public class DemoDataService {

    private static final Logger log = LoggerFactory.getLogger(DemoDataService.class);

    public static final String CHECKING_ID = "ACC-CHK-001";
    public static final String SAVINGS_ID = "ACC-SAV-001";
    public static final BigDecimal CHECKING_BALANCE = new BigDecimal("52368.50");
    public static final BigDecimal SAVINGS_BALANCE = new BigDecimal("120000.00");

    private final DemoCustomerRepository customers;
    private final BankAccountRepository accounts;
    private final BeneficiaryRepository beneficiaries;
    private final BankTransactionRepository transactions;
    private final TransferDraftRepository drafts;
    private final PaymentAuditRepository audits;
    private final BankAgentProperties properties;
    private final Clock clock;

    public DemoDataService(DemoCustomerRepository customers, BankAccountRepository accounts,
                           BeneficiaryRepository beneficiaries, BankTransactionRepository transactions,
                           TransferDraftRepository drafts, PaymentAuditRepository audits,
                           BankAgentProperties properties, Clock clock) {
        this.customers = customers;
        this.accounts = accounts;
        this.beneficiaries = beneficiaries;
        this.transactions = transactions;
        this.drafts = drafts;
        this.audits = audits;
        this.properties = properties;
        this.clock = clock;
    }

    private static final int CURRENT_MONTH_ENTRIES = 8;

    private record Seed(String accountId, LocalDateTime at, TransactionDirection direction, String category,
                        String merchant, String description, String amount) {
    }

    @Transactional
    public boolean seedIfEmpty() {
        if (customers.count() > 0) {
            return false;
        }
        seed();
        return true;
    }

    @Transactional
    public void reset() {
        audits.deleteAllInBatch();
        drafts.deleteAllInBatch();
        transactions.deleteAllInBatch();
        beneficiaries.deleteAllInBatch();
        accounts.deleteAllInBatch();
        customers.deleteAllInBatch();
        seed();
    }

    private void seed() {
        String customerId = properties.demo().customerId();
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDate today = now.toLocalDate();
        LocalDate lastMonth = today.withDayOfMonth(1).minusMonths(1);

        customers.save(new DemoCustomer(customerId, "王小明（演示用户）", "DEMO 个人客户", now));
        accounts.save(new BankAccount(CHECKING_ID, customerId, AccountType.CHECKING, "演示活期账户",
                "DEMO-****-0001", "CNY", CHECKING_BALANCE, today.minusYears(3)));
        accounts.save(new BankAccount(SAVINGS_ID, customerId, AccountType.SAVINGS, "演示储蓄账户",
                "DEMO-****-0002", "CNY", SAVINGS_BALANCE, today.minusYears(3)));
        beneficiaries.save(new Beneficiary("BEN-001", customerId, "张三", "演示银行（虚构）北京分行",
                "DEMO-****-8001", "朋友", now));
        beneficiaries.save(new Beneficiary("BEN-002", customerId, "李四", "示例商业银行（虚构）上海分行",
                "DEMO-****-8002", "家人", now));
        beneficiaries.save(new Beneficiary("BEN-003", customerId, "王五", "测试农商银行（虚构）深圳分行",
                "DEMO-****-8003", "同事", now));

        List<Seed> seeds = new ArrayList<>();
        // Previous calendar month: fixed days 1-28 exist in every month.
        seeds.add(prev(lastMonth, 1, 9, TransactionDirection.DEBIT, "住房", "安居物业（演示）", "月度房租", "4500.00"));
        seeds.add(prev(lastMonth, 3, 12, TransactionDirection.DEBIT, "餐饮", "老街面馆（演示）", "午餐", "128.00"));
        seeds.add(prev(lastMonth, 5, 10, TransactionDirection.CREDIT, "工资收入", "示例科技有限公司（虚构）", "月度工资", "18000.00"));
        seeds.add(prev(lastMonth, 6, 20, TransactionDirection.DEBIT, "购物", "京选电器商城（演示）", "蓝牙耳机", "1299.00"));
        seeds.add(prev(lastMonth, 8, 8, TransactionDirection.DEBIT, "交通", "城市地铁（演示）", "地铁月票", "86.00"));
        seeds.add(prev(lastMonth, 10, 19, TransactionDirection.DEBIT, "餐饮", "湘味小厨（演示）", "朋友聚餐", "196.00"));
        seeds.add(prev(lastMonth, 12, 11, TransactionDirection.DEBIT, "水电燃气", "城市公用事业（演示）", "水电燃气费", "320.00"));
        seeds.add(prev(lastMonth, 14, 15, TransactionDirection.DEBIT, "购物", "优品家居（演示）", "家居用品", "2680.00"));
        seeds.add(prev(lastMonth, 15, 16, TransactionDirection.DEBIT, TransactionService.TRANSFER_CATEGORY, "转账-李四", "生活费", "1000.00"));
        seeds.add(prev(lastMonth, 17, 21, TransactionDirection.DEBIT, "娱乐", "星光影城（演示）", "电影票", "150.00"));
        seeds.add(prev(lastMonth, 19, 18, TransactionDirection.DEBIT, "交通", "滴滴出行（演示）", "打车", "120.00"));
        seeds.add(prev(lastMonth, 20, 14, TransactionDirection.DEBIT, "医疗", "康泰药房（演示）", "药品", "132.00"));
        seeds.add(prev(lastMonth, 22, 20, TransactionDirection.DEBIT, "购物", "潮流服饰（演示）", "外套", "899.00"));
        seeds.add(prev(lastMonth, 24, 9, TransactionDirection.DEBIT, "餐饮", "咖啡小站（演示）", "咖啡", "80.00"));
        seeds.add(prev(lastMonth, 25, 13, TransactionDirection.CREDIT, "退款", "京选电器商城（演示）", "退货退款", "199.00"));
        seeds.add(prev(lastMonth, 27, 10, TransactionDirection.DEBIT, "通讯", "示例通信（演示）", "手机话费", "99.00"));
        // Current month: day k is capped at today and no transaction is ever later than "now".
        seeds.add(cur(now, 1, 9, TransactionDirection.DEBIT, "住房", "安居物业（演示）", "月度房租", "4500.00"));
        seeds.add(cur(now, 2, 12, TransactionDirection.DEBIT, "餐饮", "老街面馆（演示）", "午餐", "68.00"));
        seeds.add(cur(now, 3, 8, TransactionDirection.DEBIT, "交通", "城市地铁（演示）", "地铁充值", "45.00"));
        seeds.add(cur(now, 4, 20, TransactionDirection.DEBIT, "购物", "京选电器商城（演示）", "数据线", "359.00"));
        seeds.add(cur(now, 5, 10, TransactionDirection.CREDIT, "工资收入", "示例科技有限公司（虚构）", "月度工资", "18000.00"));
        seeds.add(cur(now, 6, 19, TransactionDirection.DEBIT, "餐饮", "湘味小厨（演示）", "晚餐", "152.00"));
        seeds.add(cur(now, 7, 22, TransactionDirection.DEBIT, "娱乐", "在线音乐会员（演示）", "会员续费", "88.00"));
        seeds.add(cur(now, 8, 11, TransactionDirection.DEBIT, "水电燃气", "城市公用事业（演示）", "电费", "210.00"));
        // Savings account interest.
        seeds.add(new Seed(SAVINGS_ID, lastMonth.withDayOfMonth(21).atTime(LocalTime.of(2, 0)),
                TransactionDirection.CREDIT, "利息", "演示银行（虚构）", "储蓄利息", "120.00"));

        saveWithRunningBalance(seeds, CHECKING_ID, CHECKING_BALANCE);
        saveWithRunningBalance(seeds, SAVINGS_ID, SAVINGS_BALANCE);
        log.info("Demo data seeded: customer={}, transactions={}, referenceDate={}", customerId, seeds.size(), today);
    }

    private void saveWithRunningBalance(List<Seed> all, String accountId, BigDecimal finalBalance) {
        List<Seed> seeds = all.stream().filter(s -> s.accountId().equals(accountId))
                .sorted(Comparator.comparing(Seed::at)).toList();
        BigDecimal net = BigDecimal.ZERO;
        for (Seed s : seeds) {
            BigDecimal amount = new BigDecimal(s.amount());
            net = s.direction() == TransactionDirection.CREDIT ? net.add(amount) : net.subtract(amount);
        }
        BigDecimal running = finalBalance.subtract(net);
        int sequence = 1;
        for (Seed s : seeds) {
            BigDecimal amount = new BigDecimal(s.amount());
            running = s.direction() == TransactionDirection.CREDIT ? running.add(amount) : running.subtract(amount);
            transactions.save(new BankTransaction(s.accountId(), s.at(), s.direction(), s.category(), s.merchant(),
                    s.description(), amount, "CNY", running,
                    "SEED-" + accountId + "-" + String.format("%03d", sequence++)));
        }
    }

    private static Seed prev(LocalDate lastMonth, int day, int hour, TransactionDirection direction, String category,
                             String merchant, String description, String amount) {
        return new Seed(CHECKING_ID, lastMonth.withDayOfMonth(day).atTime(hour, 0), direction, category, merchant,
                description, amount);
    }

    private static Seed cur(LocalDateTime now, int day, int hour, TransactionDirection direction, String category,
                            String merchant, String description, String amount) {
        LocalDate today = now.toLocalDate();
        LocalDate date = today.withDayOfMonth(Math.min(day, today.getDayOfMonth()));
        // When several entries collapse onto the same capped day, the minute offset keeps a stable order.
        LocalDateTime at = date.atTime(hour, day);
        if (at.isAfter(now)) {
            // Move entries that would be in the future to just before "now", but never before the month start,
            // so the previous month's totals stay identical on every calendar day.
            LocalDateTime monthStart = today.withDayOfMonth(1).atStartOfDay();
            LocalDateTime shifted = now.minusMinutes(CURRENT_MONTH_ENTRIES + 1L - day);
            at = shifted.isBefore(monthStart) ? monthStart : shifted;
        }
        return new Seed(CHECKING_ID, at, direction, category, merchant, description, amount);
    }
}
