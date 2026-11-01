package com.demo.bankagent.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request bodies accepted by the REST API. */
public final class ApiRequests {

    private ApiRequests() {
    }

    public record ChatRequest(@Size(max = 64) String conversationId,
                              @NotBlank @Size(max = 4000) String message) {
    }

    public record DraftActionRequest(@NotBlank @Size(max = 64) String conversationId) {
    }
}
