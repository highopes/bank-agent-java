package com.demo.bankagent.control;

/**
 * One control that the server evaluated for a step (an entry of EvaluationResponse.matches, errors or
 * non_matches). Used to enforce the decision and to emit the official control span.
 */
public record ControlMatchResult(
        Category category,
        long controlId,
        String controlName,
        String action,
        boolean matched,
        double confidence,
        String message,
        String errorMessage,
        String steeringContext,
        String evaluatorName,
        String selectorPath,
        String selectedPreview,
        String controlExecutionId,
        Double executionDurationMs) {

    public enum Category { MATCH, NON_MATCH, ERROR }
}
