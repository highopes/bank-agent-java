package com.demo.bankagent.observability;

/**
 * Splunk AO stores an OpenTelemetry trace ID (32 hex) as a UUIDv4: the version nibble is forced to 4 and the
 * variant bits to RFC 4122 (observed during protocol verification: OTel b8e9945f6b751f313e8d6a84fe3a2b1d -> AO
 * b8e9945f-6b75-4f31-be8d-6a84fe3a2b1d). The raw OTel ID is also kept as the record's "External Id".
 */
public final class AoIds {

    private AoIds() {
    }

    public static String aoTraceUuid(String otelTraceId) {
        if (otelTraceId == null || otelTraceId.length() != 32) {
            return null;
        }
        char[] hex = otelTraceId.toLowerCase().toCharArray();
        hex[12] = '4';
        int variant = Character.digit(hex[16], 16);
        if (variant < 0) {
            return null;
        }
        hex[16] = Character.forDigit((variant & 0x3) | 0x8, 16);
        String s = new String(hex);
        return s.substring(0, 8) + "-" + s.substring(8, 12) + "-" + s.substring(12, 16) + "-" + s.substring(16, 20)
                + "-" + s.substring(20);
    }
}
