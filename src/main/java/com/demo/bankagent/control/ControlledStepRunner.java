package com.demo.bankagent.control;

import com.demo.bankagent.control.ControlOutcome.Verdict;
import com.demo.bankagent.observability.AoSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Central PRE/POST check used at every enforcement point. It asks the {@link ControlGateway}, reports the result
 * (UI execution trace, step span attributes, official control spans) and turns it into a {@link ControlOutcome}:
 * DENY and STEER come only from a real server decision; an unavailable service is mapped with the configured
 * failure mode (default: read steps fail-open, write steps fail-closed) and is never presented as "allowed".
 */
@Component
public class ControlledStepRunner {

    private static final Logger log = LoggerFactory.getLogger(ControlledStepRunner.class);

    private final ControlGateway gateway;
    private final ControlEventReporter reporter;
    private final ControlFailureMode readFailureMode;
    private final ControlFailureMode writeFailureMode;
    private final int maxSteerRetries;

    public ControlledStepRunner(ControlGateway gateway, ControlEventReporter reporter, AoSettings settings) {
        this.gateway = gateway;
        this.reporter = reporter;
        this.readFailureMode = settings.readFailureMode().value();
        this.writeFailureMode = settings.writeFailureMode().value();
        this.maxSteerRetries = settings.maxSteerRetries().value();
    }

    public boolean enabled() {
        return gateway.enabled();
    }

    public int maxSteerRetries() {
        return maxSteerRetries;
    }

    public ControlOutcome check(ControlStage stage, ControlStep step) {
        if (!gateway.enabled()) {
            return new ControlOutcome(Verdict.PROCEED, ControlDecision.skipped("CONTROL_MODE=off", 0));
        }
        long start = System.nanoTime();
        ControlDecision decision;
        try {
            decision = gateway.evaluate(stage, step);
        } catch (ControlTransportException e) {
            decision = ControlDecision.unavailable(e.code(), e.getMessage(), java.util.List.of(), elapsed(start));
        } catch (RuntimeException e) {
            decision = ControlDecision.unavailable("CLIENT_ERROR", e.getClass().getSimpleName(), java.util.List.of(),
                    elapsed(start));
        }
        Verdict verdict = verdict(step, decision);
        reporter.report(stage, step, decision, verdict.name());
        if (verdict == Verdict.PROCEED_UNCHECKED || verdict == Verdict.BLOCKED_UNAVAILABLE) {
            log.warn("Agent Control unavailable for {} {} ({}: {}); {} -> {}", stage.wire(), step.name(),
                    decision.failureCode(), decision.reason(), failureMode(step).key(), verdict);
        } else if (verdict != Verdict.PROCEED || decision.status() == ControlDecision.Status.EVALUATED) {
            log.info("Agent Control {} {} -> {} (action={}, control={}, {} ms)", stage.wire(), step.name(), verdict,
                    decision.action(), decision.controlName(), decision.latencyMs());
        }
        return new ControlOutcome(verdict, decision);
    }

    private Verdict verdict(ControlStep step, ControlDecision decision) {
        return switch (decision.status()) {
            case SKIPPED -> Verdict.PROCEED;
            case UNAVAILABLE -> failureMode(step) == ControlFailureMode.FAIL_OPEN
                    ? Verdict.PROCEED_UNCHECKED : Verdict.BLOCKED_UNAVAILABLE;
            case EVALUATED -> switch (decision.action()) {
                case DENY -> Verdict.DENY;
                case STEER -> Verdict.STEER;
                case OBSERVE, ALLOW -> Verdict.PROCEED;
            };
        };
    }

    public ControlFailureMode failureMode(ControlStep step) {
        return step.access() == StepAccess.WRITE ? writeFailureMode : readFailureMode;
    }

    private static long elapsed(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }
}
