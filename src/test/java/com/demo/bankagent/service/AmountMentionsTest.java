package com.demo.bankagent.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class AmountMentionsTest {

    @Test
    void extractsArabicAmountsWithSeparatorsDecimalsAndUnits() {
        assertThat(AmountMentions.extract("向张三转账 500 元。")).anySatisfy(v -> assertThat(v).isEqualByComparingTo("500"));
        assertThat(AmountMentions.mentions("转 1,200.50 元给李四", new BigDecimal("1200.50"))).isTrue();
        assertThat(AmountMentions.mentions("转1.5万给王五", new BigDecimal("15000"))).isTrue();
        assertThat(AmountMentions.mentions("转2千", new BigDecimal("2000"))).isTrue();
        assertThat(AmountMentions.mentions("transfer 3k", new BigDecimal("3000"))).isTrue();
        assertThat(AmountMentions.mentions("向一个未登记的陌生收款人转账 999999 元。", new BigDecimal("999999"))).isTrue();
    }

    @Test
    void extractsChineseNumerals() {
        assertThat(AmountMentions.mentions("转三百元给张三", new BigDecimal("300"))).isTrue();
        assertThat(AmountMentions.mentions("转两千五百元", new BigDecimal("2500"))).isTrue();
        assertThat(AmountMentions.mentions("转十五元", new BigDecimal("15"))).isTrue();
        assertThat(AmountMentions.mentions("转一万零五十元", new BigDecimal("10050"))).isTrue();
        assertThat(AmountMentions.parseChinese("十")).isEqualTo(10);
        assertThat(AmountMentions.parseChinese("三千零二")).isEqualTo(3002);
    }

    @Test
    void doesNotInventAmountsThatWereNotWritten() {
        assertThat(AmountMentions.extract("这笔转账失败的原因是什么？")).isEmpty();
        assertThat(AmountMentions.mentions("按刚才那个金额再给李四转一次。", new BigDecimal("999999"))).isFalse();
        assertThat(AmountMentions.mentions("再给他转 300 元。", new BigDecimal("500"))).isFalse();
        assertThat(AmountMentions.mentions(null, new BigDecimal("1"))).isFalse();
        assertThat(AmountMentions.mentions("500", null)).isFalse();
    }

    @Test
    void amountComparisonIgnoresScale() {
        assertThat(AmountMentions.mentions("再给他转 300 元。", new BigDecimal("300.00"))).isTrue();
    }
}
