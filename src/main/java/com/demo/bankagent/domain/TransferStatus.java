package com.demo.bankagent.domain;

/** Lifecycle: PENDING_CONFIRMATION -> EXECUTED | CANCELLED | EXPIRED | FAILED. */
public enum TransferStatus {
    PENDING_CONFIRMATION,
    EXECUTED,
    CANCELLED,
    EXPIRED,
    FAILED;

    public boolean isFinal() {
        return this != PENDING_CONFIRMATION;
    }
}
