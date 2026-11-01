package com.demo.bankagent.api;

import java.time.Instant;

/** Uniform error body. Never contains stack traces or secrets. */
public record ApiError(String code, String message, int status, Instant timestamp) {
}
