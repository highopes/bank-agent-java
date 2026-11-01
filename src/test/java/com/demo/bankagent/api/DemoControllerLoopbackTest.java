package com.demo.bankagent.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DemoControllerLoopbackTest {

    @Test
    void onlyLoopbackAddressesAreAccepted() {
        assertThat(DemoController.isLoopback("127.0.0.1")).isTrue();
        assertThat(DemoController.isLoopback("0:0:0:0:0:0:0:1")).isTrue();
        assertThat(DemoController.isLoopback("10.0.0.5")).isFalse();
        assertThat(DemoController.isLoopback("192.168.1.20")).isFalse();
        assertThat(DemoController.isLoopback("")).isFalse();
        assertThat(DemoController.isLoopback(null)).isFalse();
    }
}
