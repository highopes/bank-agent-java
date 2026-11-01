package com.demo.bankagent.service;

import java.math.BigDecimal;
import java.util.Optional;

/** Lenient parser for amounts produced by the LLM, for example "500", "500元", "¥1,200.50", "1.5万". */
public final class AmountParser {

    private AmountParser() {
    }

    public static Optional<BigDecimal> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String text = raw.trim()
                .replace(",", "")
                .replace("，", "")
                .replace(" ", "")
                .replace("¥", "")
                .replace("￥", "")
                .replaceAll("(?i)(CNY|RMB)", "")
                .replace("人民币", "")
                .replace("元", "")
                .replace("块", "");
        BigDecimal multiplier = BigDecimal.ONE;
        if (text.endsWith("万")) {
            multiplier = BigDecimal.valueOf(10_000);
            text = text.substring(0, text.length() - 1);
        } else if (text.endsWith("千")) {
            multiplier = BigDecimal.valueOf(1_000);
            text = text.substring(0, text.length() - 1);
        }
        if (text.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new BigDecimal(text).multiply(multiplier).stripTrailingZeros());
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
