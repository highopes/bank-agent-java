package com.demo.bankagent.control;

/** Whether a step only reads data or changes business state; selects the failure mode when Control is down. */
public enum StepAccess {
    READ,
    WRITE
}
