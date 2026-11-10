package com.demo.bankagent.tool;

import com.demo.bankagent.conversation.ConversationService;
import com.demo.bankagent.governance.GovernedToolExecutor;
import com.demo.bankagent.service.PaymentService;
import com.demo.bankagent.trace.DraftEventTrace;
import com.demo.bankagent.trace.ExecutionTrace;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Withdraws a just-created transfer draft when Agent Control blocks the output of the write tool (Control only).
 * The withdrawal is a normal, audited cancellation (code CONTROL_WITHDRAWN); nothing is rolled back silently.
 */
@Component
public class TransferToolSideEffects implements GovernedToolExecutor.ToolSideEffects {

    private final PaymentService paymentService;
    private final ConversationService conversationService;
    private final ObjectMapper mapper;

    public TransferToolSideEffects(PaymentService paymentService, ConversationService conversationService,
                                   ObjectMapper mapper) {
        this.paymentService = paymentService;
        this.conversationService = conversationService;
        this.mapper = mapper;
    }

    @Override
    public Optional<String> withdraw(String toolName, String conversationId, String resultText, String reason) {
        if ("cancelPendingTransfer".equals(toolName)) {
            return Optional.of("The cancellation itself was already performed.");
        }
        if (!"createTransferDraft".equals(toolName) || conversationId == null) {
            return Optional.empty();
        }
        Optional<String> draftId = createdDraftId(resultText);
        if (draftId.isEmpty()) {
            return Optional.empty();
        }
        String customerId = conversationService.requireCustomerId(conversationId);
        paymentService.withdrawByControl(draftId.get(), conversationId, customerId, reason);
        ExecutionTrace.current().ifPresent(t -> {
            t.setPendingDraft(null);
            t.addDraftEvent(new DraftEventTrace("WITHDRAWN", draftId.get(), "CANCELLED"));
        });
        return Optional.of("A transfer draft had already been created and has now been withdrawn (cancelled and "
                + "audited). No money was moved; the user may send a new, adjusted request.");
    }

    private Optional<String> createdDraftId(String resultText) {
        try {
            JsonNode node = mapper.readTree(resultText);
            if (node.path("created").asBoolean(false)) {
                return Optional.ofNullable(node.path("draft").path("draftId").asText(null));
            }
        } catch (Exception ignored) {
            // not a draft result
        }
        return Optional.empty();
    }

    /** Non-reversible short reference of a draft ID for telemetry (the ID itself is not exported). */
    public static String draftRef(String draftId) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(draftId.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 6);
        } catch (NoSuchAlgorithmException e) {
            return "unavailable";
        }
    }
}
