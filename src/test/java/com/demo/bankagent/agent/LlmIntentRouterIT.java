package com.demo.bankagent.agent;

import com.demo.bankagent.conversation.Conversation;
import com.demo.bankagent.support.IntegrationTestBase;
import com.demo.bankagent.support.ScriptedChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LlmIntentRouterIT extends IntegrationTestBase {

    @Autowired
    LlmIntentRouter router;

    @Test
    void routesThroughTheModelWithStructuredJsonOutput() {
        model.route("{\"intent\":\"AGENTS\",\"steps\":[{\"agent\":\"ACCOUNT\",\"task\":\"查询余额\"},"
                + "{\"agent\":\"TRANSACTIONS\",\"task\":\"列出最近五笔交易\"}],\"reply\":\"\"}");

        RoutingDecision decision = router.route("把我的账户余额和最近五笔交易一起告诉我。", List.of());

        assertThat(decision.intent()).isEqualTo(RoutingDecision.Intent.AGENTS);
        assertThat(decision.steps()).extracting(RoutingDecision.Step::agent)
                .containsExactly(AgentType.ACCOUNT, AgentType.TRANSACTIONS);
        assertThat(model.requests()).singleElement().satisfies(request -> {
            assertThat(ScriptedChatModel.isRouter(request)).isTrue();
            assertThat(ScriptedChatModel.userTexts(request)).singleElement().asString()
                    .contains("把我的账户余额和最近五笔交易一起告诉我。");
        });
    }

    @Test
    void invalidOrEmptyDecisionsFallBackToClarify() {
        for (String json : new String[]{null, "", "not json", "{\"intent\":\"DANCE\"}",
                "{\"intent\":\"AGENTS\",\"steps\":[]}",
                "{\"intent\":\"AGENTS\",\"steps\":[{\"agent\":\"LOANS\",\"task\":\"x\"}]}",
                "{\"intent\":\"AGENTS\",\"steps\":[{\"agent\":\"ACCOUNT\",\"task\":\"\"}]}"}) {
            RoutingDecision decision = router.parse(json);
            assertThat(decision.intent()).as(String.valueOf(json)).isEqualTo(RoutingDecision.Intent.CLARIFY);
            assertThat(decision.fallback()).as(String.valueOf(json)).isTrue();
            assertThat(decision.steps()).isEmpty();
        }
    }

    @Test
    void outOfScopeKeepsTheModelReplyAndStepsAreCapped() {
        RoutingDecision outOfScope = router.parse("{\"intent\":\"OUT_OF_SCOPE\",\"steps\":[],\"reply\":\"无法处理\"}");
        assertThat(outOfScope.intent()).isEqualTo(RoutingDecision.Intent.OUT_OF_SCOPE);
        assertThat(outOfScope.reply()).isEqualTo("无法处理");

        RoutingDecision capped = router.parse("{\"intent\":\"AGENTS\",\"steps\":["
                + "{\"agent\":\"ACCOUNT\",\"task\":\"a\"},{\"agent\":\"TRANSACTIONS\",\"task\":\"b\"},"
                + "{\"agent\":\"PAYMENTS\",\"task\":\"c\"},{\"agent\":\"ACCOUNT\",\"task\":\"d\"}],\"reply\":\"\"}");
        assertThat(capped.steps()).hasSize(properties.agent().maxRouteSteps());
    }

    @Test
    void historyIsIncludedInTheRouterInput() {
        model.routeTo("PAYMENTS", "向张三再转300元");
        router.route("再给他转 300 元。", List.of(
                new Conversation.Turn(Conversation.Role.USER, "向张三转账 500 元。", Instant.now()),
                new Conversation.Turn(Conversation.Role.ASSISTANT, "已创建草稿。", Instant.now())));

        assertThat(ScriptedChatModel.userTexts(model.requests().get(0))).singleElement().asString()
                .contains("User: 向张三转账 500 元。").contains("Latest user message:\n再给他转 300 元。");
    }
}
