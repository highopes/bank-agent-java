package com.demo.bankagent.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** What may leave the process in span attributes (task book 3 / 7.2). */
class TraceContentPolicyTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void contentIsOmittedByDefault() throws Exception {
        TraceContentPolicy policy = new TraceContentPolicy(false, 100, mapper);

        assertThat(policy.text("向张三转账 500 元")).isEqualTo(TraceContentPolicy.OMITTED);
        assertThat(policy.messages("user", "向张三转账 500 元", null)).doesNotContain("张三")
                .contains(TraceContentPolicy.OMITTED);
        assertThat(policy.toolArguments(mapper.readTree("{\"beneficiary\":\"张三\",\"amount\":\"500\"}")))
                .isEqualTo("{\"captured\":false,\"argumentNames\":[\"beneficiary\",\"amount\"]}");
        assertThat(policy.toolResult("{\"balance\":52368.5}")).doesNotContain("52368").contains("resultChars");
    }

    @Test
    void optInContentIsMaskedAndTruncated() {
        TraceContentPolicy policy = new TraceContentPolicy(true, 40, mapper);

        String masked = policy.text("key sk-proj-ABCdef1234567890 account 6222020200112233 ok");
        assertThat(masked).doesNotContain("ABCdef1234567890").doesNotContain("62220202").contains("****2233");
        assertThat(policy.text("x".repeat(100))).hasSize(40 + "...[truncated]".length());
        assertThat(policy.messages("assistant", "余额 52,368.50 元", "stop"))
                .isEqualTo("[{\"role\":\"assistant\",\"parts\":[{\"type\":\"text\",\"content\":\"余额 52,368.50 元\"}],"
                        + "\"finish_reason\":\"stop\"}]");
    }
}
