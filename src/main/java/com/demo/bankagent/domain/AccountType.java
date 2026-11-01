package com.demo.bankagent.domain;

public enum AccountType {
    CHECKING("活期账户"),
    SAVINGS("储蓄账户");

    private final String label;

    AccountType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
