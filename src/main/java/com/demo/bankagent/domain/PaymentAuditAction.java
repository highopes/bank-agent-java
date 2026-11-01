package com.demo.bankagent.domain;

public enum PaymentAuditAction {
    VALIDATION,
    DRAFT_CREATED,
    DRAFT_REJECTED,
    DRAFT_SUPERSEDED,
    CONFIRM_EXECUTED,
    CONFIRM_REJECTED,
    CONFIRM_REPLAYED,
    CANCELLED,
    CANCEL_REJECTED,
    EXPIRED
}
