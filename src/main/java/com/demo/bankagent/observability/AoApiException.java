package com.demo.bankagent.observability;

/** A failed call to the AO REST API. The message is safe to log: it never contains credentials. */
public class AoApiException extends RuntimeException {

    private final int status;

    public AoApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    public AoApiException(String message, Throwable cause) {
        super(message, cause);
        this.status = -1;
    }

    /** HTTP status, or -1 for network errors and timeouts. */
    public int status() {
        return status;
    }

    public boolean isConflict() {
        return status == 400 || status == 409 || status == 422;
    }

    public boolean isAuthError() {
        return status == 401 || status == 403;
    }
}
