package com.demo.bankagent.observability;

import com.demo.bankagent.conversation.ChatService;
import com.demo.bankagent.control.ControlGateway;
import com.demo.bankagent.control.NoOpControlGateway;
import com.demo.bankagent.governance.StepGovernance;
import com.demo.bankagent.support.InMemoryTelemetryConfig;
import com.demo.bankagent.support.IntegrationTestBase;
import com.demo.bankagent.support.ScriptedChatModel;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import static com.demo.bankagent.observability.OtelSpanStructureIT.one;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Observe ON, Control OFF - the customer's entry path. LangChain4j runs on its default path (original ChatModel,
 * AiServices.tools(bean), no enforcement adapter) and the GenAI tree still comes out complete, only from the
 * LangChain4j listeners/hooks plus the chat-turn root span. No Control span or Control metadata exists.
 */
@Import(InMemoryTelemetryConfig.class)
class ObserveOnlySpanIT extends IntegrationTestBase {

    private static final AttributeKey<String> OP = AttributeKey.stringKey(GenAiAttributes.OPERATION_NAME);

    @Autowired
    InMemorySpanExporter spans;

    @Autowired
    ChatService chatService;

    @Autowired
    ControlGateway gateway;

    @Autowired
    StepGovernance governance;

    @BeforeEach
    void resetSpans() {
        spans.reset();
    }

    @Test
    void controlOffMeansTheFrameworkDefaultPath() {
        assertThat(gateway).isInstanceOf(NoOpControlGateway.class);
        assertThat(governance.chatModel(model, "account-agent")).isSameAs(model);
    }

    @Test
    void transferTurnHasTheFullGenAiTreeWithoutAnyControlData() {
        model.routeTo("PAYMENTS", "向张三转账500元")
                .toolCall("createTransferDraft", "{\"beneficiary\":\"张三\",\"amount\":\"500\"}")
                .answer("已创建草稿，请确认。");

        ChatService.ChatTurnResult result = chatService.chat(null, "向张三转账 500 元。");

        List<SpanData> all = spans.getFinishedSpanItems();
        SpanData root = one(all, "invoke_agent BankingAssistant");
        SpanData payments = one(all, "invoke_agent PaymentsAgent");
        SpanData tool = one(all, "execute_tool createTransferDraft");
        assertThat(all).hasSize(6);
        assertThat(payments.getParentSpanId()).isEqualTo(root.getSpanId());
        assertThat(tool.getParentSpanId()).isEqualTo(payments.getSpanId());
        assertThat(all).filteredOn(s -> "chat".equals(s.getAttributes().get(OP)))
                .hasSize(3)
                .allSatisfy(s -> assertThat(s.getName()).isEqualTo("chat " + ScriptedChatModel.MODEL_NAME));
        assertThat(all).allSatisfy(s -> {
            assertThat(s.getAttributes().get(AttributeKey.stringKey(GenAiAttributes.CONVERSATION_ID)))
                    .isEqualTo(result.conversationId());
            assertThat(s.getAttributes().get(AttributeKey.stringKey(GenAiAttributes.SPAN_KIND))).isNull();
            assertThat(s.getAttributes().asMap().keySet()).noneMatch(k -> k.getKey().startsWith("bank.control"));
        });
        assertThat(result.traceId()).isEqualTo(root.getTraceId());
        assertThat(result.controlDecisions()).isEmpty();
        assertThat(result.pendingDraft()).isNotNull();
    }
}
