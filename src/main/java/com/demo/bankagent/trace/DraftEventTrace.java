package com.demo.bankagent.trace;

/** A transfer-draft state change caused by a tool during this request (for the UI draft cards). */
public record DraftEventTrace(String event, String draftId, String status) {
}
