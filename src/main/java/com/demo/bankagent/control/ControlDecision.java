package com.demo.bankagent.control;

import java.util.List;

/**
 * Result of asking Agent Control about one step. {@code status} says whether a decision exists at all; only an
 * EVALUATED decision carries an action that came from the server.
 */
public record ControlDecision(
        Status status,
        ControlAction action,
        String reason,
        String controlName,
        Long controlId,
        String steeringContext,
        List<ControlMatchResult> results,
        long latencyMs,
        String failureCode) {

    public enum Status {
        /** The server evaluated the applicable controls. */
        EVALUATED,
        /** Nothing to evaluate: Control is off or no bound control applies to this step/stage. */
        SKIPPED,
        /** No usable decision: service unavailable, contract/auth error or evaluator error. Not a DENY. */
        UNAVAILABLE
    }

    public ControlDecision {
        results = results == null ? List.of() : List.copyOf(results);
    }

    public static ControlDecision skipped(String reason, long latencyMs) {
        return new ControlDecision(Status.SKIPPED, ControlAction.ALLOW, reason, null, null, null, List.of(),
                latencyMs, null);
    }

    public static ControlDecision unavailable(String failureCode, String reason, List<ControlMatchResult> results,
                                              long latencyMs) {
        return new ControlDecision(Status.UNAVAILABLE, null, reason, null, null, null, results, latencyMs,
                failureCode);
    }
}
