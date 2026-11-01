package com.demo.bankagent.api;

import com.demo.bankagent.conversation.ConversationService;
import com.demo.bankagent.service.AccountService;
import com.demo.bankagent.service.PaymentService;
import com.demo.bankagent.service.Views.TransferDraftView;
import com.demo.bankagent.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApiControllersIT extends IntegrationTestBase {

    @Autowired
    MockMvc mvc;

    @Autowired
    ConversationService conversations;

    @Autowired
    PaymentService payments;

    @Autowired
    AccountService accountService;

    private ResultActions postJson(String url, String body) throws Exception {
        return mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static String draftBody(String conversationId) {
        return "{\"conversationId\":\"" + conversationId + "\"}";
    }

    private String newConversation() {
        return conversations.create().getId();
    }

    private TransferDraftView draft(String conversationId, String amount) {
        return payments.createDraft(customerId(), conversationId, "张三", amount, null, null).draft();
    }

    @Test
    void chatEndpointReturnsAnswerWithTrace() throws Exception {
        model.routeTo("ACCOUNT", "查询余额").toolCall("getAccountBalance", "{}").answer("余额 52,368.50 元。");

        postJson("/api/chat", "{\"message\":\"我的余额是多少？\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversationId").isNotEmpty())
                .andExpect(jsonPath("$.answer").value("余额 52,368.50 元。"))
                .andExpect(jsonPath("$.selectedAgents[0]").value("ACCOUNT"))
                .andExpect(jsonPath("$.executedTools[0]").value("getAccountBalance"))
                .andExpect(jsonPath("$.llmCalls.length()").value(3))
                .andExpect(jsonPath("$.model").value("gpt-5-nano"));
    }

    @Test
    void chatEndpointValidatesInput() throws Exception {
        postJson("/api/chat", "{\"message\":\"   \"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        postJson("/api/chat", "{\"message\":\"" + "余".repeat(4001) + "\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        postJson("/api/chat", "{not json").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(content().string(not(containsString("Exception"))))
                .andExpect(content().string(not(containsString("at com."))));
        mvc.perform(post("/api/chat").contentType(MediaType.TEXT_PLAIN).content("hi"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
        assertThat(model.requests()).isEmpty();
    }

    @Test
    void unknownOrExpiredConversationStartsAFreshOne() throws Exception {
        model.routeTo("ACCOUNT", "查询余额").answer("好的。");

        postJson("/api/chat", "{\"conversationId\":\"expired-conversation\",\"message\":\"余额\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.newConversation").value(true))
                .andExpect(jsonPath("$.conversationId").value(not("expired-conversation")));
    }

    @Test
    void llmFailureIsReportedWithoutStackTrace() throws Exception {
        postJson("/api/chat", "{\"message\":\"余额\"}")
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("LLM_UPSTREAM_ERROR"))
                .andExpect(content().string(not(containsString("at com."))));
    }

    @Test
    void confirmExecutesOnceAndReplaysDuplicates() throws Exception {
        String conv = newConversation();
        TransferDraftView d = draft(conv, "500");
        String url = "/api/payments/drafts/" + d.draftId() + "/confirm";

        postJson(url, draftBody(conv)).andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.draft.status").value("EXECUTED"))
                .andExpect(jsonPath("$.balanceAfter").value(51868.50));
        postJson(url, draftBody(conv)).andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true));
        assertThat(accountService.getBalance(customerId(), null).balance()).isEqualByComparingTo("51868.50");

        postJson("/api/payments/drafts/" + d.draftId() + "/cancel", draftBody(conv))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DRAFT_ALREADY_EXECUTED"));
    }

    @Test
    void cancelThenConfirmIsRejected() throws Exception {
        String conv = newConversation();
        TransferDraftView d = draft(conv, "300");

        postJson("/api/payments/drafts/" + d.draftId() + "/cancel", draftBody(conv)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        postJson("/api/payments/drafts/" + d.draftId() + "/confirm", draftBody(conv))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DRAFT_CANCELLED"));
        assertThat(accountService.getBalance(customerId(), null).balance()).isEqualByComparingTo("52368.50");
    }

    @Test
    void draftActionsAreScopedToTheirConversation() throws Exception {
        String owner = newConversation();
        String other = newConversation();
        TransferDraftView d = draft(owner, "300");
        String confirm = "/api/payments/drafts/" + d.draftId() + "/confirm";

        postJson(confirm, draftBody(other)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("DRAFT_CONVERSATION_MISMATCH"));
        postJson(confirm, draftBody("no-such-conversation")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CONVERSATION_NOT_FOUND"));
        postJson("/api/payments/drafts/00000000-0000-0000-0000-000000000000/confirm", draftBody(owner))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DRAFT_NOT_FOUND"));
        postJson(confirm, "{}").andExpect(status().isBadRequest());
        mvc.perform(post(confirm).contentType(MediaType.TEXT_PLAIN).content(owner))
                .andExpect(status().isUnsupportedMediaType());
        assertThat(accountService.getBalance(customerId(), null).balance()).isEqualByComparingTo("52368.50");
    }

    @Test
    void resetIsLocalOnly() throws Exception {
        String conv = newConversation();
        payments.confirm(draft(conv, "500").draftId(), conv, customerId());

        mvc.perform(post("/api/demo/reset").with(request -> {
                    request.setRemoteAddr("10.0.0.5");
                    return request;
                }))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("LOCAL_ACCESS_ONLY"));
        assertThat(accountService.getBalance(customerId(), null).balance()).isEqualByComparingTo("51868.50");

        mvc.perform(post("/api/demo/reset")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESET"));
        assertThat(accountService.getBalance(customerId(), null).balance()).isEqualByComparingTo("52368.50");
        assertThat(conversations.find(conv)).isEmpty();
    }

    @Test
    void uiHealthStatusAndProfileExposeNoSecrets() throws Exception {
        mvc.perform(get("/")).andExpect(status().isOk())
                .andExpect(content().string(containsString("DEMO ACCOUNT")))
                .andExpect(content().string(containsString("Simulated Banking")))
                .andExpect(content().string(not(containsString("sk-"))));
        mvc.perform(get("/actuator/health")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/api/status")).andExpect(status().isOk())
                .andExpect(jsonPath("$.model").value("gpt-5-nano"))
                .andExpect(jsonPath("$.llmConfigured").value(false))
                .andExpect(content().string(not(containsString("apiKey"))))
                .andExpect(content().string(not(containsString("sk-"))));
        mvc.perform(get("/api/demo/profile")).andExpect(status().isOk())
                .andExpect(jsonPath("$.beneficiaries.length()").value(3))
                .andExpect(content().string(not(containsString("DEMO-0001"))));
    }
}
