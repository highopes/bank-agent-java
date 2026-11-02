package com.demo.bankagent.observability;

import com.demo.bankagent.api.PaymentController;
import com.demo.bankagent.control.ControlStage;
import com.demo.bankagent.conversation.ChatService;
import com.demo.bankagent.service.Views.TransferDraftView;
import com.demo.bankagent.support.GovernanceTestBase;
import com.demo.bankagent.support.ScriptedControlGateway;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanId;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Span structure produced by the real application code path (Supervisor -> AiServices -> governed model / tools ->
 * H2), recorded with an in-memory exporter. Verifies real parent/child relations, token values, content policy,
 * error recording and trace separation (task book 9.1 OtelSpanStructureIT).
 */
class OtelSpanStructureIT extends GovernanceTestBase {

    private static final AttributeKey<String> OP = AttributeKey.stringKey(GenAiAttributes.OPERATION_NAME);
    private static final AttributeKey<String> CONV = AttributeKey.stringKey(GenAiAttributes.CONVERSATION_ID);

    @Autowired
    ChatService chatService;

    @Autowired
    MockMvc mvc;

    @Autowired
    com.demo.bankagent.service.PaymentService payments;

    @Test
    void balanceTurnIsOneTraceWithRealParentChildRelations() {
        model.routeTo("ACCOUNT", "查询活期余额")
                .toolCall("getAccountBalance", "{\"accountType\":\"CHECKING\"}")
                .answer("您的活期账户余额为 52,368.50 元。");

        ChatService.ChatTurnResult result = chatService.chat(null, "我现在的活期账户余额是多少？");

        List<SpanData> all = spans.getFinishedSpanItems();
        SpanData root = one(all, "invoke_agent BankingAssistant");
        SpanData supervisor = one(all, "invoke_agent SupervisorAgent");
        SpanData account = one(all, "invoke_agent AccountAgent");
        SpanData tool = one(all, "execute_tool getAccountBalance");
        List<SpanData> llm = all.stream().filter(s -> "chat".equals(s.getAttributes().get(OP))).toList();

        assertThat(root.getParentSpanId()).isEqualTo(SpanId.getInvalid());
        assertThat(all).allSatisfy(s -> assertThat(s.getTraceId()).isEqualTo(root.getTraceId()));
        assertThat(result.traceId()).isEqualTo(root.getTraceId());
        assertThat(result.aoTraceId()).isEqualTo(AoIds.aoTraceUuid(root.getTraceId()));
        assertThat(supervisor.getParentSpanId()).isEqualTo(root.getSpanId());
        assertThat(account.getParentSpanId()).isEqualTo(supervisor.getSpanId());
        assertThat(tool.getParentSpanId()).isEqualTo(account.getSpanId());
        assertThat(llm).hasSize(3);
        assertThat(llm).filteredOn(s -> s.getParentSpanId().equals(supervisor.getSpanId())).hasSize(1);
        assertThat(llm).filteredOn(s -> s.getParentSpanId().equals(account.getSpanId())).hasSize(2);
        assertThat(llm).allSatisfy(s -> {
            assertThat(s.getKind()).isEqualTo(SpanKind.CLIENT);
            assertThat(s.getAttributes().get(AttributeKey.longKey(GenAiAttributes.USAGE_INPUT_TOKENS))).isEqualTo(11L);
            assertThat(s.getAttributes().get(AttributeKey.longKey(GenAiAttributes.USAGE_OUTPUT_TOKENS))).isEqualTo(7L);
            assertThat(s.getAttributes().get(AttributeKey.stringKey(GenAiAttributes.PROVIDER_NAME))).isEqualTo("openai");
        });
        assertThat(tool.getAttributes().get(AttributeKey.stringKey(GenAiAttributes.TOOL_NAME)))
                .isEqualTo("getAccountBalance");
        assertThat(tool.getStartEpochNanos()).isLessThanOrEqualTo(tool.getEndEpochNanos());
        assertThat(all).allSatisfy(s -> assertThat(s.getAttributes().get(CONV)).isEqualTo(result.conversationId()));
        assertThat(root.getAttributes().get(AttributeKey.stringKey(GenAiAttributes.BANK_INTENT))).isEqualTo("AGENTS");
    }

    @Test
    void sensitiveContentIsNotExportedByDefault() {
        model.routeTo("PAYMENTS", "向张三转账500元")
                .toolCall("createTransferDraft", "{\"beneficiary\":\"张三\",\"amount\":\"500\"}")
                .answer("已为张三创建 500 元的转账草稿，请确认。");

        chatService.chat(null, "向张三转账 500 元。");

        String exported = spans.getFinishedSpanItems().stream()
                .map(s -> s.getName() + s.getAttributes().toString()).reduce("", String::concat);
        assertThat(exported).doesNotContain("张三").doesNotContain("500 元").doesNotContain("52368")
                .doesNotContain("\"amount\":\"500\"").contains(TraceContentPolicy.OMITTED)
                .contains("argumentNames");
        SpanData tool = one(spans.getFinishedSpanItems(), "execute_tool createTransferDraft");
        assertThat(tool.getAttributes().get(AttributeKey.stringKey(GenAiAttributes.TOOL_CALL_ARGUMENTS)))
                .contains("beneficiary").contains("amount").doesNotContain("张三");
    }

    @Test
    void conversationsDoNotShareTraces() {
        model.routeTo("ACCOUNT", "余额").answer("A")
                .routeTo("TRANSACTIONS", "交易").answer("B");

        ChatService.ChatTurnResult first = chatService.chat(null, "余额");
        ChatService.ChatTurnResult second = chatService.chat(null, "交易");

        assertThat(first.traceId()).isNotEqualTo(second.traceId());
        assertThat(spans.getFinishedSpanItems()).allSatisfy(s -> {
            String conversation = s.getAttributes().get(CONV);
            String expected = s.getTraceId().equals(first.traceId()) ? first.conversationId() : second.conversationId();
            assertThat(conversation).isEqualTo(expected);
        });
    }

    @Test
    void crossDomainTurnHasOneAgentSpanPerDelegation() {
        model.route("{\"intent\":\"AGENTS\",\"steps\":[{\"agent\":\"ACCOUNT\",\"task\":\"余额\"},"
                        + "{\"agent\":\"TRANSACTIONS\",\"task\":\"最近五笔\"}],\"reply\":\"\"}")
                .answer("余额 52,368.50").answer("交易如下");

        chatService.chat(null, "余额和最近五笔交易一起告诉我");

        SpanData supervisor = one(spans.getFinishedSpanItems(), "invoke_agent SupervisorAgent");
        assertThat(spans.getFinishedSpanItems())
                .filteredOn(s -> s.getName().startsWith("invoke_agent ") && s.getParentSpanId()
                        .equals(supervisor.getSpanId()))
                .extracting(SpanData::getName)
                .containsExactly("invoke_agent AccountAgent", "invoke_agent TransactionsAgent");
    }

    @Test
    void llmFailureMarksSpansAsErrorWithoutStackTrace() {
        // No scripted router reply: the model call fails like an upstream error.
        try {
            chatService.chat(null, "余额");
        } catch (RuntimeException expected) {
            // asserted below
        }
        SpanData root = one(spans.getFinishedSpanItems(), "invoke_agent BankingAssistant");
        assertThat(root.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(root.getAttributes().get(AttributeKey.stringKey(GenAiAttributes.ERROR_TYPE)))
                .isEqualTo("LLM_UPSTREAM_ERROR");
        assertThat(root.getAttributes().get(AttributeKey.longKey(GenAiAttributes.AO_STATUS_CODE))).isEqualTo(502L);
        SpanData llm = spans.getFinishedSpanItems().stream()
                .filter(s -> "chat".equals(s.getAttributes().get(OP))).findFirst().orElseThrow();
        assertThat(llm.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(spans.getFinishedSpanItems()).allSatisfy(s -> s.getEvents().forEach(e ->
                assertThat(e.getAttributes().get(AttributeKey.stringKey("exception.stacktrace"))).isNull()));
    }

    @Test
    void confirmationIsAnIndependentTraceLinkedToTheDraftCreation() throws Exception {
        model.routeTo("PAYMENTS", "向张三转账500元")
                .toolCall("createTransferDraft", "{\"beneficiary\":\"张三\",\"amount\":\"500\"}")
                .answer("草稿已创建");
        ChatService.ChatTurnResult turn = chatService.chat(null, "向张三转账 500 元。");
        TransferDraftView draft = (TransferDraftView) turn.pendingDraft();
        SpanData creation = one(spans.getFinishedSpanItems(), "execute_tool createTransferDraft");
        spans.reset();

        var response = mvc.perform(post("/api/payments/drafts/" + draft.draftId() + "/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\":\"" + turn.conversationId() + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse();

        SpanData confirm = one(spans.getFinishedSpanItems(), "payment.confirm");
        assertThat(response.getHeader(PaymentController.TRACE_ID_HEADER)).isEqualTo(confirm.getTraceId());
        assertThat(response.getHeader(PaymentController.AO_TRACE_ID_HEADER))
                .isEqualTo(AoIds.aoTraceUuid(confirm.getTraceId()));
        assertThat(confirm.getParentSpanId()).isEqualTo(SpanId.getInvalid());
        assertThat(confirm.getTraceId()).isNotEqualTo(turn.traceId());
        assertThat(confirm.getLinks()).singleElement()
                .satisfies(link -> assertThat(link.getSpanContext().getSpanId()).isEqualTo(creation.getSpanId()));
        assertThat(confirm.getAttributes().get(AttributeKey.stringKey(GenAiAttributes.BANK_OPERATION)))
                .isEqualTo("confirm");
        assertThat(confirm.getAttributes().get(AttributeKey.stringKey(GenAiAttributes.BANK_OUTCOME)))
                .isEqualTo("EXECUTED");
        assertThat(confirm.getAttributes().get(CONV)).isEqualTo(turn.conversationId());
    }

    @Test
    void controlResultsBecomeOfficialControlSpansUnderTheCheckedStep() {
        control.once(ControlStage.PRE, "getAccountBalance",
                ScriptedControlGateway.observe(13, "observe-bank-tool-usage"));
        model.routeTo("ACCOUNT", "余额").toolCall("getAccountBalance", "{}").answer("余额如下");

        chatService.chat(null, "余额");

        SpanData tool = one(spans.getFinishedSpanItems(), "execute_tool getAccountBalance");
        SpanData controlSpan = one(spans.getFinishedSpanItems(), "observe-bank-tool-usage");
        assertThat(controlSpan.getParentSpanId()).isEqualTo(tool.getSpanId());
        assertThat(controlSpan.getAttributes().get(AttributeKey.stringKey(GenAiAttributes.SPAN_KIND)))
                .isEqualTo("control");
        assertThat(controlSpan.getAttributes().get(OP)).isEqualTo("control");
        assertThat(controlSpan.getAttributes().get(AttributeKey.longKey(GenAiAttributes.CONTROL_ID))).isEqualTo(13L);
        assertThat(controlSpan.getAttributes().get(AttributeKey.stringKey(GenAiAttributes.CONTROL_CHECK_STAGE)))
                .isEqualTo("pre");
        assertThat(controlSpan.getAttributes().get(AttributeKey.stringKey(GenAiAttributes.CONTROL_APPLIES_TO)))
                .isEqualTo("tool_call");
        assertThat(controlSpan.getAttributes().get(AttributeKey.stringKey(GenAiAttributes.CONTROL_ACTION)))
                .isEqualTo("observe");
        assertThat(controlSpan.getAttributes().get(AttributeKey.booleanKey(GenAiAttributes.CONTROL_MATCHED))).isTrue();
        assertThat(tool.getAttributes().get(AttributeKey.stringKey("bank.control.pre.verdict"))).isEqualTo("PROCEED");
    }

    @Test
    void aDeniedToolCallIsAFailedStepWithTheStatusAoDisplays() {
        control.once(ControlStage.PRE, "createTransferDraft",
                ScriptedControlGateway.deny(18, "deny-restricted-transfer", "restricted beneficiary"));
        model.routeTo("PAYMENTS", "向王五转账500元")
                .toolCall("createTransferDraft", "{\"beneficiary\":\"王五\",\"amount\":\"500\"}")
                .answer("无法创建草稿");

        chatService.chat(null, "向王五转账 500 元。");

        SpanData tool = one(spans.getFinishedSpanItems(), "execute_tool createTransferDraft");
        assertThat(tool.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(tool.getAttributes().get(AttributeKey.stringKey(GenAiAttributes.ERROR_TYPE)))
                .isEqualTo("control_denied");
        assertThat(tool.getAttributes().get(AttributeKey.longKey(GenAiAttributes.AO_STATUS_CODE))).isEqualTo(403L);
        SpanData root = one(spans.getFinishedSpanItems(), "invoke_agent BankingAssistant");
        assertThat(root.getAttributes().get(AttributeKey.longKey(GenAiAttributes.AO_STATUS_CODE))).isNull();
    }

    private static SpanData one(List<SpanData> spans, String name) {
        Optional<SpanData> found = spans.stream().filter(s -> s.getName().equals(name)).findFirst();
        assertThat(spans.stream().filter(s -> s.getName().equals(name))).as("span " + name).hasSize(1);
        return found.orElseThrow();
    }
}
