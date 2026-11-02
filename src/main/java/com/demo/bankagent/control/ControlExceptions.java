package com.demo.bankagent.control;

import com.demo.bankagent.service.BankingException;
import com.demo.bankagent.service.ErrorCode;

/**
 * Business-level results of Control enforcement, mapped by the web layer to friendly API errors. They extend
 * BankingException so the existing error handling (LlmErrorTranslator, GlobalExceptionHandler) passes them through
 * unchanged. Messages contain the control name and reason but never secrets or raw protected content.
 */
public final class ControlExceptions {

    private ControlExceptions() {
    }

    /** A control decided DENY: the protected step was not executed (pre) or its output is withheld (post). */
    public static class ControlDeniedException extends BankingException {
        private final ControlDecision decision;
        private final ControlStage stage;
        private final String stepName;

        public ControlDeniedException(ControlStage stage, String stepName, ControlDecision decision) {
            super(ErrorCode.CONTROL_DENIED, "Blocked by Agent Control"
                    + (decision.controlName() == null ? "" : " control '" + decision.controlName() + "'")
                    + " (" + stage.wire() + " " + stepName + ")"
                    + (decision.reason() == null ? "." : ": " + decision.reason()));
            this.decision = decision;
            this.stage = stage;
            this.stepName = stepName;
        }

        public ControlDecision decision() {
            return decision;
        }

        public ControlStage stage() {
            return stage;
        }

        public String stepName() {
            return stepName;
        }
    }

    /**
     * A control decided STEER for a step whose automatic retry is not allowed (write operations, a whole chat turn,
     * or retries exhausted). The user must adjust the request; nothing is changed automatically.
     */
    public static class ControlSteerRejectedException extends BankingException {
        private final ControlDecision decision;

        public ControlSteerRejectedException(ControlStage stage, String stepName, ControlDecision decision) {
            super(ErrorCode.CONTROL_STEER_REQUIRES_USER_ACTION, "Agent Control"
                    + (decision.controlName() == null ? "" : " control '" + decision.controlName() + "'")
                    + " requires a change before '" + stepName + "' can run (" + stage.wire() + "): "
                    + (decision.steeringContext() == null ? decision.reason() : decision.steeringContext()));
            this.decision = decision;
        }

        public ControlDecision decision() {
            return decision;
        }
    }

    /** Control could not decide and the step's failure mode is fail-closed: the step was not executed. */
    public static class ControlUnavailableException extends BankingException {
        public ControlUnavailableException(ControlStage stage, String stepName, ControlDecision decision) {
            super(ErrorCode.CONTROL_UNAVAILABLE, "Agent Control is unavailable (" + decision.failureCode() + "), so '"
                    + stepName + "' was not executed (fail-closed, " + stage.wire() + " check). This is not a "
                    + "policy decision; please retry later.");
        }
    }
}
