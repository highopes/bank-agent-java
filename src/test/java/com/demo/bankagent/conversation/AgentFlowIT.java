package com.demo.bankagent.conversation;

import com.demo.bankagent.repository.TransferDraftRepository;
import com.demo.bankagent.service.AccountService;
import com.demo.bankagent.service.PaymentService;
import com.demo.bankagent.service.Views.TransferDraftView;
import com.demo.bankagent.support.IntegrationTestBase;
import com.demo.bankagent.support.ScriptedChatModel;
import com.demo.bankagent.trace.DraftEventTrace;
import com.demo.bankagent.trace.LlmCallTrace;
import com.demo.bankagent.trace.ToolCallTrace;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end agent flows through the real Supervisor, LangChain4j AiServices, tools and H2, with a scripted
 * chat model instead of OpenAI (no network, deterministic).
 */
class AgentFlowIT extends IntegrationTestBase {

    @Autowired
    ChatService chatService;

    @Autowired
    AccountService accountService;

    @Autowired
    TransferDraftRepository drafts;

    @Autowired
    PaymentService paymentService;

    @Test
    void balanceQuestionRunsRealToolCallAndIsTraced() {
        List<String> toolResults = new ArrayList<>();
        model.routeTo("ACCOUNT", "查询活期账户余额")
                .toolCall("getAccountBalance", "{\"accountType\":\"CHECKING\"}")
                .answer(request -> {
                    toolResults.add(ScriptedChatModel.lastToolResult(request));
                    return "您的活期账户余额为 52,368.50 元。";
                });

        ChatService.ChatTurnResult result = chatService.chat(null, "我现在的活期账户余额是多少？");

        assertThat(result.newConversation()).isTrue();
        assertThat(result.intent()).isEqualTo("AGENTS");
        assertThat(result.selectedAgents()).containsExactly("ACCOUNT");
        assertThat(result.executedTools()).containsExactly("getAccountBalance");
        assertThat(result.toolCalls()).singleElement().satisfies(call -> {
            assertThat(call.agent()).isEqualTo("ACCOUNT");
            assertThat(call.success()).isTrue();
        });
        assertThat(result.llmCalls()).hasSize(3)
                .allSatisfy(call -> assertThat(call.model()).isEqualTo(ScriptedChatModel.MODEL_NAME))
                .extracting(LlmCallTrace::inputTokens).containsOnly(11);
        assertThat(result.answer()).contains("52,368.50");
        assertThat(toolResults).singleElement().asString().contains("52368.5").contains("DEMO-****-0001");
        assertThat(model.allConsumed()).isTrue();
    }

    @Test
    void crossDomainQuestionUsesTwoAgentsAndMergesSections() {
        model.route("{\"intent\":\"AGENTS\",\"steps\":[{\"agent\":\"ACCOUNT\",\"task\":\"查询账户余额\"},"
                        + "{\"agent\":\"TRANSACTIONS\",\"task\":\"列出最近五笔交易\"}],\"reply\":\"\"}")
                .toolCall("getAccountBalance", "{}")
                .answer("活期余额 52,368.50 元。")
                .toolCall("listRecentTransactions", "{\"limit\":5}")
                .answer("最近五笔交易如下……");

        ChatService.ChatTurnResult result = chatService.chat(null, "把我的账户余额和最近五笔交易一起告诉我。");

        assertThat(result.selectedAgents()).containsExactly("ACCOUNT", "TRANSACTIONS");
        assertThat(result.executedTools()).containsExactly("getAccountBalance", "listRecentTransactions");
        assertThat(result.agentSteps()).hasSize(2);
        assertThat(result.llmCalls()).hasSize(5);
        assertThat(result.answer()).contains("**账户信息**").contains("**交易记录**")
                .contains("52,368.50").contains("最近五笔交易如下");
    }

    @Test
    void transferRequestOnlyCreatesAPendingDraft() {
        model.routeTo("PAYMENTS", "向张三转账500元")
                .toolCall("createTransferDraft", "{\"beneficiary\":\"张三\",\"amount\":\"500\"}")
                .answer("已为您创建转账草稿，请在界面点击确认。");

        ChatService.ChatTurnResult result = chatService.chat(null, "向张三转账 500 元。");

        assertThat(result.pendingDraft()).isInstanceOfSatisfying(TransferDraftView.class, d -> {
            assertThat(d.status()).isEqualTo("PENDING_CONFIRMATION");
            assertThat(d.beneficiaryName()).isEqualTo("张三");
            assertThat(d.amount()).isEqualByComparingTo("500");
        });
        assertThat(result.draftEvents()).extracting(DraftEventTrace::event).containsExactly("CREATED");
        assertThat(accountService.getBalance(customerId(), null).balance()).isEqualByComparingTo("52368.50");
        assertThat(drafts.count()).isEqualTo(1);
    }

    @Test
    void amountInventedByTheModelIsBlockedByTheGuard() {
        List<String> toolResults = new ArrayList<>();
        model.routeTo("PAYMENTS", "按上次金额向李四转账")
                .toolCall("createTransferDraft", "{\"beneficiary\":\"李四\",\"amount\":\"999999\"}")
                .answer(request -> {
                    toolResults.add(ScriptedChatModel.lastToolResult(request));
                    return "您没有说明金额，无法创建草稿。";
                });

        ChatService.ChatTurnResult result = chatService.chat(null, "按刚才那个金额再给李四转一次。");

        assertThat(result.pendingDraft()).isNull();
        assertThat(result.draftEvents()).extracting(DraftEventTrace::event).containsExactly("REJECTED");
        assertThat(toolResults).singleElement().asString().contains(PaymentService.AMOUNT_NOT_STATED_BY_USER);
        assertThat(drafts.count()).isZero();
        assertThat(paymentService.recentAudit(result.conversationId())).singleElement()
                .satisfies(a -> assertThat(a.code()).isEqualTo(PaymentService.AMOUNT_NOT_STATED_BY_USER));
    }

    @Test
    void agentMemoryContinuesWithinAConversationAndIsIsolatedAcrossConversations() {
        List<List<String>> seen = new ArrayList<>();
        model.routeTo("PAYMENTS", "向张三转账500元").answer(remember(seen, "请确认收款人。"))
                .routeTo("PAYMENTS", "向张三再转300元").answer(remember(seen, "好的。"))
                .routeTo("PAYMENTS", "向王五转账100元").answer(remember(seen, "好的。"));

        String first = chatService.chat(null, "向张三转账 500 元。").conversationId();
        ChatService.ChatTurnResult second = chatService.chat(first, "再给他转 300 元。");
        ChatService.ChatTurnResult other = chatService.chat(null, "向王五转账 100 元。");

        assertThat(second.conversationId()).isEqualTo(first);
        assertThat(second.newConversation()).isFalse();
        assertThat(other.conversationId()).isNotEqualTo(first);
        assertThat(seen.get(1)).hasSize(2);
        assertThat(seen.get(1).get(0)).contains("向张三转账 500 元。");
        assertThat(seen.get(1).get(1)).contains("再给他转 300 元。");
        assertThat(seen.get(2)).singleElement().asString().contains("向王五转账 100 元。")
                .doesNotContain("张三");

        List<ChatRequest> routerRequests = model.requests().stream().filter(ScriptedChatModel::isRouter).toList();
        assertThat(ScriptedChatModel.userTexts(routerRequests.get(1))).singleElement().asString()
                .contains("User: 向张三转账 500 元。");
        assertThat(ScriptedChatModel.userTexts(routerRequests.get(2))).singleElement().asString()
                .doesNotContain("张三");
    }

    @Test
    void outOfScopeQuestionCallsNoAgentAndNoTool() {
        model.route("{\"intent\":\"OUT_OF_SCOPE\",\"steps\":[],\"reply\":\"我只能回答演示银行账户相关的问题。\"}");

        ChatService.ChatTurnResult result = chatService.chat(null, "帮我写一首诗");

        assertThat(result.intent()).isEqualTo("OUT_OF_SCOPE");
        assertThat(result.selectedAgents()).isEmpty();
        assertThat(result.toolCalls()).extracting(ToolCallTrace::tool).isEmpty();
        assertThat(result.llmCalls()).hasSize(1);
        assertThat(result.answer()).contains("演示银行");
    }

    private static java.util.function.Function<ChatRequest, String> remember(List<List<String>> seen, String reply) {
        return request -> {
            seen.add(ScriptedChatModel.userTexts(request));
            return reply;
        };
    }
}
