package com.demo.bankagent.agent;

/** The specialist domain agents the Supervisor can delegate to. */
public enum AgentType {
    ACCOUNT("Account Agent", "账户信息"),
    TRANSACTIONS("Transactions Agent", "交易记录"),
    PAYMENTS("Payments Agent", "转账服务");

    private final String displayName;
    private final String sectionTitle;

    AgentType(String displayName, String sectionTitle) {
        this.displayName = displayName;
        this.sectionTitle = sectionTitle;
    }

    public String displayName() {
        return displayName;
    }

    public String sectionTitle() {
        return sectionTitle;
    }
}
