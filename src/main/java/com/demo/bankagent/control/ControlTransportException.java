package com.demo.bankagent.control;

/**
 * Agent Control could not produce a decision: network error, timeout, HTTP 401/403/429/5xx, unexpected response.
 * This is never a DENY; ControlledStepRunner applies the configured failure mode. Messages carry no secrets.
 */
public class ControlTransportException extends RuntimeException {

    private final String code;
    private final int httpStatus;

    public ControlTransportException(String code, int httpStatus, String message) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public ControlTransportException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.httpStatus = -1;
    }

    /** TIMEOUT, NETWORK, AUTH, RATE_LIMITED, SERVER_ERROR, CONTRACT, TOKEN. */
    public String code() {
        return code;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
