package com.demo.bankagent.control;

/**
 * The decision the Java code enforces. Priority when several controls match: DENY &gt; STEER &gt; OBSERVE &gt;
 * ALLOW (Agent Control concepts; agent_control control_decorators._handle_evaluation_result).
 */
public enum ControlAction {
    ALLOW,
    DENY,
    STEER,
    OBSERVE
}
