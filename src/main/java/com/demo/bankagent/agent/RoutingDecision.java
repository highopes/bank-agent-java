package com.demo.bankagent.agent;

import java.util.List;

/** Validated output of the Supervisor's routing step. */
public record RoutingDecision(Intent intent, List<Step> steps, String reply, boolean fallback) {

    public enum Intent { AGENTS, CLARIFY, OUT_OF_SCOPE, SMALL_TALK }

    public record Step(AgentType agent, String task) {
    }

    public static RoutingDecision clarifyFallback() {
        return new RoutingDecision(Intent.CLARIFY, List.of(),
                "抱歉，我没有完全理解您的需求。您可以问我：账户余额、最近的交易、某段时间的消费分析，"
                        + "或者向已登记收款人发起（需要您在界面上确认的）模拟转账。", true);
    }
}
