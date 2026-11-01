package com.demo.bankagent.agent;

import com.demo.bankagent.agent.RoutingDecision.Intent;
import com.demo.bankagent.agent.RoutingDecision.Step;
import com.demo.bankagent.trace.ExecutionTrace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Supervisor orchestration with a stub router and a recording gateway (no LLM involved). */
class SupervisorAgentTest {

    private record Call(AgentType agent, String conversationId, String input) {
    }

    private final List<Call> calls = new ArrayList<>();

    private final DomainAgentGateway gateway = (agent, conversationId, input) -> {
        calls.add(new Call(agent, conversationId, input));
        return agent.name() + " answer";
    };

    @AfterEach
    void endTrace() {
        ExecutionTrace.end();
    }

    private SupervisorAgent supervisor(RoutingDecision decision) {
        return new SupervisorAgent((message, history) -> decision, gateway);
    }

    @Test
    void singleDomainDelegatesOnceWithVerbatimUserMessage() {
        ExecutionTrace trace = ExecutionTrace.begin("c1");
        SupervisorAgent.SupervisorResult result = supervisor(new RoutingDecision(Intent.AGENTS,
                List.of(new Step(AgentType.ACCOUNT, "查询活期余额")), "", false))
                .handle("c1", "我现在的活期账户余额是多少？", List.of());

        assertThat(result.intent()).isEqualTo("AGENTS");
        assertThat(result.selectedAgents()).containsExactly("ACCOUNT");
        assertThat(result.answer()).isEqualTo("ACCOUNT answer");
        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).input()).contains("我现在的活期账户余额是多少？").contains("查询活期余额");
        assertThat(trace.agentSteps()).singleElement()
                .satisfies(step -> assertThat(step.agent()).isEqualTo("ACCOUNT"));
    }

    @Test
    void crossDomainDelegatesToEachAgentWithOnlyItsOwnPart() {
        ExecutionTrace trace = ExecutionTrace.begin("c1");
        SupervisorAgent.SupervisorResult result = supervisor(new RoutingDecision(Intent.AGENTS, List.of(
                new Step(AgentType.ACCOUNT, "查询账户余额"),
                new Step(AgentType.TRANSACTIONS, "列出最近五笔交易")), "", false))
                .handle("c1", "把我的账户余额和最近五笔交易一起告诉我。", List.of());

        assertThat(result.selectedAgents()).containsExactly("ACCOUNT", "TRANSACTIONS");
        assertThat(calls).extracting(Call::agent).containsExactly(AgentType.ACCOUNT, AgentType.TRANSACTIONS);
        assertThat(calls.get(0).input()).contains("查询账户余额").doesNotContain("最近五笔交易");
        assertThat(result.answer()).contains("**账户信息**").contains("**交易记录**")
                .contains("ACCOUNT answer").contains("TRANSACTIONS answer");
        assertThat(trace.agentSteps()).hasSize(2);
    }

    @Test
    void clarifyAndOutOfScopeDoNotCallAnyAgent() {
        SupervisorAgent.SupervisorResult clarify = supervisor(RoutingDecision.clarifyFallback())
                .handle("c1", "嗯？", List.of());
        SupervisorAgent.SupervisorResult outOfScope = supervisor(new RoutingDecision(Intent.OUT_OF_SCOPE,
                List.of(), "我只能处理演示银行账户相关的问题。", false)).handle("c1", "今天天气如何", List.of());

        assertThat(calls).isEmpty();
        assertThat(clarify.intent()).isEqualTo("CLARIFY");
        assertThat(clarify.answer()).contains("账户余额");
        assertThat(outOfScope.selectedAgents()).isEmpty();
        assertThat(outOfScope.answer()).contains("演示银行");
    }

    @Test
    void duplicateStepsForTheSameAgentRunOnlyOnce() {
        supervisor(new RoutingDecision(Intent.AGENTS, List.of(
                new Step(AgentType.PAYMENTS, "向陌生收款人转账999999元"),
                new Step(AgentType.PAYMENTS, "创建另一个草稿")), "", false))
                .handle("c1", "向一个未登记的陌生收款人转账 999999 元。", List.of());

        assertThat(calls).singleElement().satisfies(call -> {
            assertThat(call.agent()).isEqualTo(AgentType.PAYMENTS);
            assertThat(call.input()).contains("向一个未登记的陌生收款人转账 999999 元。");
        });
    }

    @Test
    void agentFailureIsRecordedAndPropagated() {
        ExecutionTrace trace = ExecutionTrace.begin("c1");
        SupervisorAgent failing = new SupervisorAgent((m, h) -> new RoutingDecision(Intent.AGENTS,
                List.of(new Step(AgentType.ACCOUNT, "查询余额")), "", false),
                (agent, id, input) -> {
                    throw new IllegalStateException("boom");
                });

        assertThatThrownBy(() -> failing.handle("c1", "余额", List.of())).isInstanceOf(IllegalStateException.class);
        assertThat(trace.agentSteps()).singleElement()
                .satisfies(step -> assertThat(step.status()).isEqualTo("FAILED"));
    }

    @Test
    void agentInputMarksUserMessageAsAuthoritativeForSingleAgent() {
        assertThat(SupervisorAgent.agentInput("再给他转 300 元。", "向张三转账300元", false))
                .startsWith("[User message - authoritative]\n再给他转 300 元。")
                .contains("向张三转账300元");
        assertThat(SupervisorAgent.agentInput("余额和交易", "查询余额", true))
                .contains("查询余额").doesNotContain("余额和交易");
    }
}
