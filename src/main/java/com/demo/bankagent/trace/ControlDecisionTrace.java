package com.demo.bankagent.trace;

/**
 * One Agent Control check performed during this request (for the UI debug panel). {@code verdict} is what the
 * Java code did: PROCEED, PROCEED_UNCHECKED (fail-open), DENY, STEER, BLOCKED_UNAVAILABLE (fail-closed).
 */
public record ControlDecisionTrace(String stage, String stepType, String stepName, String verdict, String action,
                                   String controlName, Long controlId, String reason, long latencyMs,
                                   int evaluatedControls) {
}
