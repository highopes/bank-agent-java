package com.demo.bankagent.observability;

import java.util.Locale;
import java.util.Optional;

/**
 * Backend configuration profile (AO_BACKEND_PROFILE). It selects the derivation rules, the credential and the
 * header names; see docs/CONFIG-MAPPING.md section 2.
 */
public enum AoBackendProfile {
    /** Option A: Splunk AO SaaS on Splunk Observability Cloud (realm + O11y access token, X-SF-Token). */
    O11Y("o11y"),
    /** Options B/C: Galileo SaaS or Galileo multi-tenant cloud (Galileo API key, Galileo-API-Key). */
    GALILEO("galileo");

    private final String key;

    AoBackendProfile(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    public static Optional<AoBackendProfile> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String value = raw.strip().toLowerCase(Locale.ROOT);
        for (AoBackendProfile profile : values()) {
            if (profile.key.equals(value)) {
                return Optional.of(profile);
            }
        }
        return Optional.empty();
    }
}
