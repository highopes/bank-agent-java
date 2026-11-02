package com.demo.bankagent.control;

/** When a step is checked: before it runs (pre) or after it produced an output (post). */
public enum ControlStage {
    PRE("pre"),
    POST("post");

    private final String wire;

    ControlStage(String wire) {
        this.wire = wire;
    }

    /** Value used by the Agent Control API ("pre" / "post"). */
    public String wire() {
        return wire;
    }
}
