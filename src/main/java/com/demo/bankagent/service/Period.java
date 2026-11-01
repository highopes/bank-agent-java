package com.demo.bankagent.service;

import java.time.LocalDate;
import java.util.Locale;

/** Supported reporting periods. Date ranges are inclusive and computed from the demo clock. */
public enum Period {
    THIS_MONTH,
    LAST_MONTH,
    LAST_7_DAYS,
    LAST_30_DAYS,
    LAST_90_DAYS,
    CUSTOM;

    public record Range(LocalDate from, LocalDate to) {
    }

    public static Period parse(String raw, Period fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Period.valueOf(raw.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_'));
        } catch (IllegalArgumentException e) {
            throw new BankingException(ErrorCode.VALIDATION_ERROR,
                    "Unsupported period '" + raw + "'. Use THIS_MONTH, LAST_MONTH, LAST_7_DAYS, LAST_30_DAYS, "
                            + "LAST_90_DAYS or CUSTOM with fromDate/toDate.");
        }
    }

    public Range resolve(LocalDate today, LocalDate customFrom, LocalDate customTo) {
        return switch (this) {
            case THIS_MONTH -> new Range(today.withDayOfMonth(1), today);
            case LAST_MONTH -> {
                LocalDate first = today.withDayOfMonth(1).minusMonths(1);
                yield new Range(first, first.withDayOfMonth(first.lengthOfMonth()));
            }
            case LAST_7_DAYS -> new Range(today.minusDays(6), today);
            case LAST_30_DAYS -> new Range(today.minusDays(29), today);
            case LAST_90_DAYS -> new Range(today.minusDays(89), today);
            case CUSTOM -> {
                if (customFrom == null || customTo == null) {
                    throw new BankingException(ErrorCode.VALIDATION_ERROR,
                            "CUSTOM period requires both fromDate and toDate (yyyy-MM-dd).");
                }
                if (customTo.isBefore(customFrom)) {
                    throw new BankingException(ErrorCode.VALIDATION_ERROR, "toDate must not be before fromDate.");
                }
                yield new Range(customFrom, customTo);
            }
        };
    }
}
