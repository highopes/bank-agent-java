package com.demo.bankagent.llm;

import com.demo.bankagent.service.BankingException;
import com.demo.bankagent.service.ErrorCode;
import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.exception.ModelNotFoundException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.exception.TimeoutException;

/** Converts LangChain4j / OpenAI failures into safe business errors without leaking request details. */
public final class LlmErrorTranslator {

    private LlmErrorTranslator() {
    }

    public static BankingException translate(RuntimeException e) {
        if (e instanceof BankingException be) {
            return be;
        }
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root && !(root instanceof BankingException)) {
            root = root.getCause();
        }
        if (root instanceof BankingException be) {
            return be;
        }
        String message;
        if (e instanceof AuthenticationException) {
            message = "OpenAI rejected the API key (authentication failed). Check the private secrets file.";
        } else if (e instanceof ModelNotFoundException) {
            message = "The configured OpenAI model is not available for this API key. Check OPENAI_MODEL.";
        } else if (e instanceof RateLimitException) {
            message = "OpenAI rate limit or quota exceeded. Please retry later.";
        } else if (e instanceof TimeoutException) {
            message = "OpenAI did not respond in time. Please retry.";
        } else if (e instanceof InvalidRequestException) {
            message = "OpenAI rejected the request as invalid: " + shorten(SecretMasker.mask(e.getMessage()));
        } else {
            message = "OpenAI call failed: " + shorten(SecretMasker.mask(e.getMessage()));
        }
        return new BankingException(ErrorCode.LLM_UPSTREAM_ERROR, message);
    }

    private static String shorten(String text) {
        if (text == null) {
            return "unknown error";
        }
        String oneLine = text.replaceAll("\\s+", " ");
        return oneLine.length() > 240 ? oneLine.substring(0, 240) + "..." : oneLine;
    }
}
