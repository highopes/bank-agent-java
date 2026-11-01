package com.demo.bankagent.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AmountParserTest {

    @Test
    void parsesCommonFormats() {
        assertThat(AmountParser.parse("500")).hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("500"));
        assertThat(AmountParser.parse("¥1,200.50元")).hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("1200.5"));
        assertThat(AmountParser.parse("1.5万")).hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("15000"));
        assertThat(AmountParser.parse("300 CNY")).hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("300"));
    }

    @Test
    void rejectsGarbage() {
        assertThat(AmountParser.parse(null)).isEmpty();
        assertThat(AmountParser.parse("")).isEmpty();
        assertThat(AmountParser.parse("很多钱")).isEmpty();
    }
}
