package com.demo.bankagent.agent;

import com.demo.bankagent.llm.SecretMasker;
import com.demo.bankagent.llm.UnconfiguredChatModel;
import com.demo.bankagent.service.BankingException;
import com.demo.bankagent.service.ErrorCode;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmSafetyUnitTest {

    @Test
    void unconfiguredModelFailsFastWithoutFabricatingAnswers() {
        ChatRequest request = ChatRequest.builder().messages(UserMessage.from("余额")).build();
        assertThatThrownBy(() -> new UnconfiguredChatModel().chat(request))
                .isInstanceOfSatisfying(BankingException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.LLM_NOT_CONFIGURED));
    }

    @Test
    void secretMaskerHidesKeysAndBearerTokens() {
        String masked = SecretMasker.mask("key sk-proj-ABCdef1234567890 header Bearer abcdefghijklmnop");
        assertThat(masked).doesNotContain("ABCdef1234567890").doesNotContain("abcdefghijklmnop")
                .contains("sk-***").contains("Bearer ***");
    }
}
