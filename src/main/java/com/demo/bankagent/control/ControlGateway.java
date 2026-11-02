package com.demo.bankagent.control;

/**
 * Project-internal Control contract. Business code depends only on this interface (via ControlledStepRunner);
 * the HTTP details live in {@link RemoteAgentControlGateway}, tests use mocks, CONTROL_MODE=off uses
 * {@link NoOpControlGateway}.
 */
public interface ControlGateway {

    /**
     * Evaluates one step. Returns SKIPPED when nothing applies, EVALUATED with the enforced action, or UNAVAILABLE
     * when the server answered but could not evaluate (evaluator errors).
     *
     * @throws ControlTransportException when the service cannot be reached or answers outside the contract
     */
    ControlDecision evaluate(ControlStage stage, ControlStep step);

    /** False only for the off mode; the runner then performs no checks at all. */
    boolean enabled();

    /** Short secret-free status text for /api/status. */
    String describe();
}
