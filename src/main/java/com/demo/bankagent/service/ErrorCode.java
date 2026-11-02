package com.demo.bankagent.service;

import org.springframework.http.HttpStatus;

/** Business error codes exposed to API clients and to the LLM tools (never stack traces). */
public enum ErrorCode {
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST),
    INVALID_AMOUNT(HttpStatus.UNPROCESSABLE_ENTITY),
    AMOUNT_EXCEEDS_LIMIT(HttpStatus.UNPROCESSABLE_ENTITY),
    INSUFFICIENT_FUNDS(HttpStatus.UNPROCESSABLE_ENTITY),
    BENEFICIARY_NOT_REGISTERED(HttpStatus.UNPROCESSABLE_ENTITY),
    ACCOUNT_NOT_AVAILABLE(HttpStatus.UNPROCESSABLE_ENTITY),
    CONVERSATION_NOT_FOUND(HttpStatus.NOT_FOUND),
    CONVERSATION_BUSY(HttpStatus.CONFLICT),
    DRAFT_NOT_FOUND(HttpStatus.NOT_FOUND),
    DRAFT_CONVERSATION_MISMATCH(HttpStatus.FORBIDDEN),
    DRAFT_ALREADY_EXECUTED(HttpStatus.CONFLICT),
    DRAFT_CANCELLED(HttpStatus.CONFLICT),
    DRAFT_EXPIRED(HttpStatus.CONFLICT),
    DRAFT_FAILED(HttpStatus.CONFLICT),
    LOCAL_ACCESS_ONLY(HttpStatus.FORBIDDEN),
    LLM_NOT_CONFIGURED(HttpStatus.SERVICE_UNAVAILABLE),
    LLM_UPSTREAM_ERROR(HttpStatus.BAD_GATEWAY),
    /** Phase 2: an Agent Control policy denied the step (never a technical failure). */
    CONTROL_DENIED(HttpStatus.FORBIDDEN),
    /** Phase 2: a STEER decision on a step that must not be changed automatically; the user has to adjust it. */
    CONTROL_STEER_REQUIRES_USER_ACTION(HttpStatus.CONFLICT),
    /** Phase 2: Agent Control could not decide and the step is fail-closed (no new side effect). */
    CONTROL_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
