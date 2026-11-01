package com.demo.bankagent.service;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts the amounts a user literally stated in a message ("500元", "1,200.50", "1.5万", "三百", "两千五百").
 * Used as a deterministic guard: a transfer draft may only be created for an amount the user actually wrote
 * in the current message, so an LLM can never invent or carry over an amount on its own.
 */
public final class AmountMentions {

    private static final Pattern ARABIC = Pattern.compile("(\\d{1,3}(?:[,，]\\d{3})+|\\d+)(\\.\\d+)?\\s*(万|千|[kK])?");
    private static final Pattern CHINESE = Pattern.compile("[零〇一二两三四五六七八九十百千万亿]{1,12}");
    private static final String DIGITS = "零一二三四五六七八九";

    private AmountMentions() {
    }

    public static Set<BigDecimal> extract(String text) {
        Set<BigDecimal> amounts = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return amounts;
        }
        Matcher arabic = ARABIC.matcher(text);
        while (arabic.find()) {
            String number = arabic.group(1).replace(",", "").replace("，", "")
                    + (arabic.group(2) == null ? "" : arabic.group(2));
            BigDecimal value = new BigDecimal(number);
            String unit = arabic.group(3);
            if ("万".equals(unit)) {
                value = value.multiply(BigDecimal.valueOf(10_000));
            } else if ("千".equals(unit) || "k".equalsIgnoreCase(unit)) {
                value = value.multiply(BigDecimal.valueOf(1_000));
            }
            add(amounts, value);
        }
        Matcher chinese = CHINESE.matcher(text);
        while (chinese.find()) {
            add(amounts, BigDecimal.valueOf(parseChinese(chinese.group())));
        }
        return amounts;
    }

    public static boolean mentions(String text, BigDecimal amount) {
        if (amount == null) {
            return false;
        }
        return extract(text).stream().anyMatch(a -> a.compareTo(amount) == 0);
    }

    private static void add(Set<BigDecimal> amounts, BigDecimal value) {
        if (value.signum() > 0) {
            amounts.add(value.stripTrailingZeros());
        }
    }

    static long parseChinese(String s) {
        long total = 0;
        long section = 0;
        long number = 0;
        for (char c : s.toCharArray()) {
            int digit = c == '〇' ? 0 : c == '两' ? 2 : DIGITS.indexOf(c);
            if (digit >= 0) {
                number = digit;
                continue;
            }
            switch (c) {
                case '十' -> section += (number == 0 ? 1 : number) * 10;
                case '百' -> section += number * 100;
                case '千' -> section += number * 1_000;
                case '万' -> {
                    total += (section + number) * 10_000;
                    section = 0;
                }
                case '亿' -> {
                    total = (total + section + number) * 100_000_000L;
                    section = 0;
                }
                default -> {
                    // not reachable: the pattern only matches numeral characters
                }
            }
            number = 0;
        }
        return total + section + number;
    }
}
