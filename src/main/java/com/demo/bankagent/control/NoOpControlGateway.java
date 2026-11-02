package com.demo.bankagent.control;

/** CONTROL_MODE=off: every step is allowed and no network call is ever made. */
public class NoOpControlGateway implements ControlGateway {

    @Override
    public ControlDecision evaluate(ControlStage stage, ControlStep step) {
        return ControlDecision.skipped("CONTROL_MODE=off", 0);
    }

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public String describe() {
        return "off (no Agent Control requests)";
    }
}
