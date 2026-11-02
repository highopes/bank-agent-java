package com.demo.bankagent.control;

/**
 * What the calling code must do after a check. The caller (tool executor, chat model decorator, chat service,
 * payment controller) enforces it; Agent Control never stops Java code by itself.
 */
public record ControlOutcome(Verdict verdict, ControlDecision decision) {

    public enum Verdict {
        /** Run the step (no control applied, ALLOW or OBSERVE). */
        PROCEED,
        /** Control could not decide and the step is fail-open: run it, but it was NOT checked. */
        PROCEED_UNCHECKED,
        /** A control decided DENY. */
        DENY,
        /** A control decided STEER; steeringContext() holds the guidance. */
        STEER,
        /** Control could not decide and the step is fail-closed: do not run it. */
        BLOCKED_UNAVAILABLE
    }

    public boolean proceed() {
        return verdict == Verdict.PROCEED || verdict == Verdict.PROCEED_UNCHECKED;
    }

    public String steeringContext() {
        String steer = decision.steeringContext();
        return steer == null || steer.isBlank() ? decision.reason() : steer;
    }
}
