package com.demo.bankagent.tool;

import com.demo.bankagent.service.AmountParser;
import com.demo.bankagent.service.Period;
import com.demo.bankagent.service.TransactionService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

/** Tools available to the Transactions agent. Totals are computed in Java, not by the model. */
@Component
public class TransactionTools {

    private final TransactionService transactionService;
    private final ToolSupport support;

    public TransactionTools(TransactionService transactionService, ToolSupport support) {
        this.transactionService = transactionService;
        this.support = support;
    }

    @Tool(name = "listRecentTransactions",
            value = "List the most recent transactions, newest first. Amounts are positive; direction DEBIT = money out, CREDIT = money in.")
    public String listRecentTransactions(
            @ToolMemoryId String conversationId,
            @P(value = "Number of transactions to return (1-50, default 5)", required = false) Integer limit,
            @P(value = "Account: CHECKING, SAVINGS or ALL (default ALL)", required = false)
            String accountType) {
        String customerId = support.customerId(conversationId);
        return support.json("transactions",
                transactionService.listRecent(customerId, ToolSupport.blankToNull(accountType), limit));
    }

    @Tool(name = "searchTransactions",
            value = "Search transactions by date range, direction, category, merchant keyword and amount range. All filters are optional.")
    public String searchTransactions(
            @ToolMemoryId String conversationId,
            @P(value = "Start date inclusive, yyyy-MM-dd", required = false) String fromDate,
            @P(value = "End date inclusive, yyyy-MM-dd", required = false) String toDate,
            @P(value = "DEBIT (spending), CREDIT (income) or ALL", required = false) String direction,
            @P(value = "Category in Chinese, e.g. 餐饮, 购物, 交通, 住房, 娱乐, 医疗, 水电燃气, 通讯, 转账, 工资收入, 退款, 利息",
                    required = false) String category,
            @P(value = "Keyword contained in the merchant name or description", required = false) String merchantKeyword,
            @P(value = "Minimum amount in CNY", required = false) String minAmount,
            @P(value = "Maximum amount in CNY", required = false) String maxAmount,
            @P(value = "Account: CHECKING, SAVINGS or ALL (default ALL)", required = false) String accountType,
            @P(value = "Maximum number of results (1-50, default 20)", required = false) Integer limit) {
        String customerId = support.customerId(conversationId);
        TransactionService.SearchCriteria criteria = new TransactionService.SearchCriteria(
                ToolSupport.blankToNull(accountType),
                ToolSupport.parseDate(fromDate, "fromDate"),
                ToolSupport.parseDate(toDate, "toDate"),
                ToolSupport.blankToNull(direction),
                ToolSupport.blankToNull(category),
                ToolSupport.blankToNull(merchantKeyword),
                amount(minAmount),
                amount(maxAmount),
                limit);
        return support.json("transactions", transactionService.search(customerId, criteria));
    }

    @Tool(name = "summarizeSpending",
            value = "Aggregate spending by category for a period and return totals, category ranking and the top category. "
                    + "Use this for any question about how much was spent or which category was highest.")
    public String summarizeSpending(
            @ToolMemoryId String conversationId,
            @P(value = "THIS_MONTH, LAST_MONTH, LAST_7_DAYS, LAST_30_DAYS, LAST_90_DAYS or CUSTOM (default THIS_MONTH)",
                    required = false) String period,
            @P(value = "Start date yyyy-MM-dd, only for CUSTOM", required = false) String fromDate,
            @P(value = "End date yyyy-MM-dd, only for CUSTOM", required = false) String toDate,
            @P(value = "Account: CHECKING, SAVINGS or ALL (default ALL)", required = false) String accountType,
            @P(value = "Whether outgoing transfers to beneficiaries count as spending (default false)", required = false)
            Boolean includeTransfers) {
        String customerId = support.customerId(conversationId);
        return support.json(transactionService.summarizeSpending(customerId,
                Period.parse(period, Period.THIS_MONTH),
                ToolSupport.parseDate(fromDate, "fromDate"),
                ToolSupport.parseDate(toDate, "toDate"),
                ToolSupport.blankToNull(accountType),
                Boolean.TRUE.equals(includeTransfers)));
    }

    private static BigDecimal amount(String raw) {
        String value = ToolSupport.blankToNull(raw);
        return value == null ? null : AmountParser.parse(value).orElse(null);
    }
}
