package com.demo.bankagent.tool;

import com.demo.bankagent.service.AccountService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import org.springframework.stereotype.Component;

/** Tools available to the Account agent. All data comes from the H2 database via AccountService. */
@Component
public class AccountTools {

    private final AccountService accountService;
    private final ToolSupport support;

    public AccountTools(AccountService accountService, ToolSupport support) {
        this.accountService = accountService;
        this.support = support;
    }

    @Tool(name = "getAccountBalance", value = "Get the current balance and currency of the customer's demo account(s).")
    public String getAccountBalance(
            @ToolMemoryId String conversationId,
            @P(value = "Which account: CHECKING (活期, default), SAVINGS (储蓄) or ALL", required = false)
            String accountType) {
        String customerId = support.customerId(conversationId);
        String ref = ToolSupport.blankToNull(accountType);
        if (ref != null && ("ALL".equalsIgnoreCase(ref) || ref.contains("全部") || ref.contains("所有"))) {
            return support.json("accounts", accountService.listAccounts(customerId));
        }
        return support.json(accountService.getBalance(customerId, ref));
    }

    @Tool(name = "getAccountSummary",
            value = "Get a masked overview of the customer profile, all demo accounts, total balance and beneficiary count.")
    public String getAccountSummary(@ToolMemoryId String conversationId) {
        return support.json(accountService.getSummary(support.customerId(conversationId)));
    }

    @Tool(name = "listBeneficiaries", value = "List the customer's registered (whitelisted) transfer beneficiaries.")
    public String listBeneficiaries(@ToolMemoryId String conversationId) {
        return support.json("beneficiaries", accountService.listBeneficiaries(support.customerId(conversationId)));
    }

    @Tool(name = "listPaymentMethods",
            value = "List the customer's accounts that can or cannot be used as a source for transfers/payments.")
    public String listPaymentMethods(@ToolMemoryId String conversationId) {
        return support.json("paymentMethods", accountService.listPaymentMethods(support.customerId(conversationId)));
    }
}
