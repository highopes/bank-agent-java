package com.demo.bankagent.governance;

import com.demo.bankagent.api.PaymentController;
import com.demo.bankagent.control.ControlGateway;
import com.demo.bankagent.control.NoOpControlGateway;
import com.demo.bankagent.conversation.ChatService;
import com.demo.bankagent.observability.AoTargetHolder;
import com.demo.bankagent.observability.BankTelemetry;
import com.demo.bankagent.service.AccountService;
import com.demo.bankagent.service.Views.TransferDraftView;
import com.demo.bankagent.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OBSERVABILITY_ENABLED=false and CONTROL_MODE=off (the defaults): the application must behave exactly like
 * Phase 1 - no backend lookup, no spans, no Control requests (task book 9.1 ControlDisabledRegressionIT). The
 * Phase 1 test classes themselves run unchanged in this mode as well.
 */
class ControlDisabledRegressionIT extends IntegrationTestBase {

    @Autowired
    ControlGateway gateway;

    @Autowired
    BankTelemetry telemetry;

    @Autowired
    AoTargetHolder targets;

    @Autowired
    ChatService chatService;

    @Autowired
    AccountService accountService;

    @Autowired
    MockMvc mvc;

    @Test
    void switchesOffMeanNoControlNoTelemetryNoLookup() {
        assertThat(gateway).isInstanceOf(NoOpControlGateway.class);
        assertThat(gateway.enabled()).isFalse();
        assertThat(telemetry.exporting()).isFalse();
        assertThat(targets.state()).isEqualTo(AoTargetHolder.State.DISABLED);
        assertThat(targets.target()).isEmpty();
    }

    @Test
    void chatTurnIsUnchangedAndCarriesNoControlOrTraceData() {
        model.routeTo("PAYMENTS", "向张三转账500元")
                .toolCall("createTransferDraft", "{\"beneficiary\":\"张三\",\"amount\":\"500\"}")
                .answer("已创建草稿。");

        ChatService.ChatTurnResult result = chatService.chat(null, "向张三转账 500 元。");

        assertThat(result.selectedAgents()).containsExactly("PAYMENTS");
        assertThat(result.executedTools()).containsExactly("createTransferDraft");
        assertThat(result.llmCalls()).hasSize(3);
        assertThat(result.pendingDraft()).isNotNull();
        assertThat(result.controlDecisions()).isEmpty();
        assertThat(result.traceId()).isNull();
        assertThat(result.aoTraceId()).isNull();
        assertThat(accountService.getBalance(customerId(), null).balance()).isEqualByComparingTo("52368.50");
    }

    @Test
    void confirmBehavesLikePhase1WithoutTraceHeaders() throws Exception {
        model.routeTo("PAYMENTS", "向张三转账500元")
                .toolCall("createTransferDraft", "{\"beneficiary\":\"张三\",\"amount\":\"500\"}")
                .answer("已创建草稿。");
        ChatService.ChatTurnResult turn = chatService.chat(null, "向张三转账 500 元。");
        TransferDraftView draft = (TransferDraftView) turn.pendingDraft();

        mvc.perform(post("/api/payments/drafts/" + draft.draftId() + "/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\":\"" + turn.conversationId() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.draft.status").value("EXECUTED"))
                .andExpect(header().doesNotExist(PaymentController.TRACE_ID_HEADER))
                .andExpect(header().doesNotExist(PaymentController.AO_TRACE_ID_HEADER));
        assertThat(accountService.getBalance(customerId(), null).balance()).isEqualByComparingTo("51868.50");
    }

    @Test
    void statusReportsBothFeaturesOffWithoutSecrets() throws Exception {
        mvc.perform(get("/api/status")).andExpect(status().isOk())
                .andExpect(jsonPath("$.observability.enabled").value(false))
                .andExpect(jsonPath("$.control.mode").value("off"))
                .andExpect(jsonPath("$.control.eventChannel").value(
                        org.hamcrest.Matchers.startsWith("EVENT_INGEST_UNAVAILABLE")))
                .andExpect(jsonPath("$.observability.otlpHost").doesNotExist());
    }
}
