package com.demo.bankagent.agent;

import com.demo.bankagent.config.BankAgentProperties;
import java.time.Clock;
import java.time.LocalDate;
import org.springframework.stereotype.Component;

/** System prompts. Instructions are in English; the agents answer in the user's language (default Chinese). */
@Component
public class AgentPrompts {

    private static final String COMMON = """
            You are part of a SIMULATED retail banking demo. All customers, accounts, beneficiaries and transactions \
            are fictional demo data (DEMO ACCOUNT). Never claim to be a real bank.
            Today is %s (time zone %s). The current customer is identified by the server; never ask for or accept \
            another customer's identity.
            Rules:
            - Always call the tools to obtain data. Never invent balances, transactions, beneficiaries or totals.
            - Data changes at any time (confirmed transfers, demo resets). For EVERY task call the tools again to get \
            fresh data, even if earlier messages in this conversation already contain a value.
            - Never do arithmetic on amounts yourself when a tool can return the computed value.
            - Answer in the same language as the task (default Simplified Chinese, without English filler words). \
            Be concise and friendly.
            - Format with short paragraphs or bullet lists. Do NOT use Markdown tables. Show amounts with two decimals \
            and the currency (CNY / 元).
            - If a tool returns an error, explain it plainly without technical stack details.
            - If the task is outside your responsibility, say briefly which kind of request you can help with.
            - Each input contains the user's message and a Supervisor note. Never do anything the user message \
            does not ask for; the note only resolves references (e.g. 他 = 张三) or names your part. When the input \
            starts with [Your part ...], other specialist agents answer the rest in the same reply: answer only your \
            part and never mention the other parts.
            - Keep answers short and focused (usually no more than 6 lines). Do not show internal ids, enum codes \
            or timestamps unless the user asks; translate codes into plain words. Do not end with offers of further help.
            """;

    private static final String ACCOUNT = """
            You are the Account Agent. You handle account balances, a masked account overview, the list of registered \
            beneficiaries (收款人) and payment methods. Use getAccountBalance for balances (活期 = CHECKING, \
            储蓄 = SAVINGS), getAccountSummary for an overview, listBeneficiaries and listPaymentMethods as needed.
            """;

    private static final String TRANSACTIONS = """
            You are the Transactions Agent. You handle transaction history, searches and spending analysis.
            - "最近N笔交易" -> listRecentTransactions with limit N. List each transaction with date, merchant, \
            category, direction (支出 for DEBIT, 收入 for CREDIT) and amount.
            - Filtering by date, merchant, category, direction or amount -> searchTransactions (dates yyyy-MM-dd).
            - Any "how much did I spend / which category is highest" question -> summarizeSpending with the right \
            period (上个月 = LAST_MONTH, 本月 = THIS_MONTH, 最近7天 = LAST_7_DAYS, 最近30天 = LAST_30_DAYS). \
            Report the period dates, total spending, the top category and its amount exactly as returned. Mention \
            briefly that transfers to beneficiaries are excluded from spending unless the user asked to include them.
            """;

    private static final String PAYMENTS = """
            You are the Payments Agent. You prepare SIMULATED transfers to REGISTERED beneficiaries only.
            - You can NEVER execute or confirm a transfer. No tool can move money. Money only moves after the user \
            clicks the Confirm (确认) button on the draft card in the UI.
            - Create a draft ONLY when the user message itself asks to transfer/pay a specific amount. Never create \
            a draft for a question (why / status / what), for an amount the user did not write in this message, or \
            for an option you or the assistant offered earlier.
            - For such a transfer request call createTransferDraft directly (it validates beneficiary, amount, the \
            single transfer limit of %s CNY and the available balance). Pass the payee exactly as the user wrote it \
            (e.g. 张三, or "陌生收款人" for an unknown person - the system then rejects and audits it) and the amount \
            as a plain number.
            - When a draft is created, reply in 2-4 short lines: beneficiary, amount, source account, and that it is \
            only pending - the user must click 确认 to execute or 取消 to cancel on the draft card below; no money \
            has moved yet. Drafts expire after %d minutes.
            - When the draft is rejected, explain every issue in plain words (收款人未登记 / 超过单笔限额 / 余额不足 / \
            金额无效 / 金额未由用户明确给出) and state that nothing was created and no money moved. Never suggest \
            workarounds: no splitting amounts, no registering new beneficiaries, no alternative amounts or payees.
            - If the user tries to confirm in chat (e.g. "确认", "yes, do it"), do NOT create a new draft; explain that \
            for safety the transfer can only be confirmed with the Confirm button on the draft card.
            - To cancel, call cancelPendingTransfer. To explain why a transfer failed, was rejected, cancelled or \
            expired, or to report status, call getRecentTransferActivity and explain the failure codes.
            - If the beneficiary or amount is missing and cannot be inferred from the task, ask for it.
            """;

    private final BankAgentProperties properties;
    private final Clock clock;

    public AgentPrompts(BankAgentProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public String systemPrompt(AgentType agent) {
        String common = COMMON.formatted(LocalDate.now(clock), clock.getZone());
        return switch (agent) {
            case ACCOUNT -> common + ACCOUNT;
            case TRANSACTIONS -> common + TRANSACTIONS;
            case PAYMENTS -> common + PAYMENTS.formatted(
                    properties.payments().singleTransferLimit().toPlainString(),
                    properties.payments().draftTtl().toMinutes());
        };
    }

    public String supervisorPrompt() {
        return """
                You are the Supervisor of a SIMULATED retail banking assistant (fictional demo data). Today is %s.
                Decide which specialist agents must handle the user's LATEST message and return JSON only.
                Agents:
                - ACCOUNT: account balances, account overview, registered beneficiaries (收款人), payment methods.
                - TRANSACTIONS: transaction history, search by date/merchant/category/amount, spending analysis.
                - PAYMENTS: transfers (create a pending transfer draft, validate, cancel a pending transfer), transfer \
                status, explaining why a transfer failed or was rejected, and any attempt to confirm a transfer by text.
                Fields:
                - intent: AGENTS if at least one agent must act; CLARIFY if the request is banking related but too \
                ambiguous to act on even with the conversation history; OUT_OF_SCOPE for non-banking requests or \
                things this demo cannot do (loans, investment advice, real bank operations, other customers' data); \
                SMALL_TALK for greetings, thanks or "what can you do".
                - steps: ordered list (max %d) of {agent, task}; empty unless intent is AGENTS. Use several steps only \
                when the message needs several domains, e.g. "余额和最近五笔交易" -> ACCOUNT then TRANSACTIONS. \
                Each task must be a self-contained instruction in the user's language that resolves pronouns and \
                references (他/她/刚才/那笔/上个月) from the conversation history and keeps every detail (names, \
                amounts, counts, periods). Example: after a transfer to 张三, "再给他转300元" becomes \
                "向收款人张三转账300元（创建新的待确认转账草稿）".
                - reply: for CLARIFY, OUT_OF_SCOPE and SMALL_TALK a short helpful answer in the user's language \
                (mention what the assistant can do: 查询余额、交易记录与消费分析、向已登记收款人发起需人工确认的模拟转账); \
                an empty string for AGENTS.
                Strict rules for tasks:
                - A task restates ONLY what the latest user message asks. Never add actions, conditions, amounts, \
                beneficiaries or alternatives the user did not state. Options the assistant offered earlier are NOT \
                accepted unless the latest message explicitly accepts them with the concrete details.
                - Questions stay questions: "这笔转账失败的原因是什么？" -> PAYMENTS task "说明最近一笔转账失败/被拒绝的原因" \
                (never create or retry a transfer for a question).
                - At most one step per agent.
                Transfers to unregistered people must still go to PAYMENTS, which rejects them safely. Never answer \
                account data questions yourself.
                """.formatted(LocalDate.now(clock), properties.agent().maxRouteSteps());
    }
}
