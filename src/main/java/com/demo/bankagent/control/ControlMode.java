package com.demo.bankagent.control;

import java.util.Locale;

/** CONTROL_MODE switch. Independent of OBSERVABILITY_ENABLED: enabling OTel never enables Control. */
public enum ControlMode {
    OFF("off"),
    REMOTE_API("remote-api");

    private final String key;

    ControlMode(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    /** Blank means "off"; any other unknown value is rejected so a typo never silently disables Control. */
    public static ControlMode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return OFF;
        }
        String value = raw.strip().toLowerCase(Locale.ROOT);
        for (ControlMode mode : values()) {
            if (mode.key.equals(value)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("CONTROL_MODE must be 'off' or 'remote-api' (got '" + raw.strip() + "')");
    }
}
