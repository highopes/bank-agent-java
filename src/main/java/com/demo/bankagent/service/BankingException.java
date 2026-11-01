package com.demo.bankagent.service;

/** A safe, explainable business error. The message must never contain secrets. */
public class BankingException extends RuntimeException {

    private final ErrorCode code;

    public BankingException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode getCode() {
        return code;
    }
}
