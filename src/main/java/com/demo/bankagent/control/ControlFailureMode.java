package com.demo.bankagent.control;

import java.util.Locale;

/**
 * What to do when Agent Control cannot produce a decision (timeout, 5xx, auth or contract error, evaluator
 * error). This is never a Control DENY: the UI and logs say "Control unavailable", not "checked and allowed".
 */
public enum ControlFailureMode {
    /** Run the step anyway and report that it was not checked. */
    FAIL_OPEN("fail-open"),
    /** Do not run the step (no new side effect). */
    FAIL_CLOSED("fail-closed");

    private final String key;

    ControlFailureMode(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    public static ControlFailureMode parse(String raw, String setting) {
        String value = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
        for (ControlFailureMode mode : values()) {
            if (mode.key.equals(value)) {
                return mode;
            }
        }
        throw new IllegalArgumentException(setting + " must be 'fail-open' or 'fail-closed' (got '" + raw + "')");
    }
}
