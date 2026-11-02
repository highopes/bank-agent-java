package com.demo.bankagent.support;

import com.demo.bankagent.control.ControlAction;
import com.demo.bankagent.control.ControlDecision;
import com.demo.bankagent.control.ControlGateway;
import com.demo.bankagent.control.ControlMatchResult;
import com.demo.bankagent.control.ControlStage;
import com.demo.bankagent.control.ControlStep;
import com.demo.bankagent.control.ControlTransportException;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * Offline stand-in for the Agent Control server. Tests script a decision per (stage, step name); unscripted steps
 * are SKIPPED (no control applies), exactly like a server without bound controls. Every call is recorded.
 */
public class ScriptedControlGateway implements ControlGateway {

    public record Call(ControlStage stage, ControlStep step) {
    }

    private final Map<String, Deque<Function<ControlStep, ControlDecision>>> rules = new ConcurrentHashMap<>();
    private final Map<String, Function<ControlStep, ControlDecision>> sticky = new ConcurrentHashMap<>();
    private final List<Call> calls = new CopyOnWriteArrayList<>();

    public void reset() {
        rules.clear();
        sticky.clear();
        calls.clear();
    }

    /** Next evaluation of this stage/step returns the decision (one-shot, FIFO). */
    public ScriptedControlGateway once(ControlStage stage, String step, ControlDecision decision) {
        rules.computeIfAbsent(key(stage, step), k -> new ConcurrentLinkedDeque<>()).add(s -> decision);
        return this;
    }

    /** Every evaluation of this stage/step returns the decision. */
    public ScriptedControlGateway always(ControlStage stage, String step, ControlDecision decision) {
        sticky.put(key(stage, step), s -> decision);
        return this;
    }

    /** Every evaluation of this stage/step fails like an unreachable service. */
    public ScriptedControlGateway unavailable(ControlStage stage, String step) {
        sticky.put(key(stage, step), s -> {
            throw new ControlTransportException("TIMEOUT", 0, "simulated timeout");
        });
        return this;
    }

    public List<Call> calls() {
        return List.copyOf(calls);
    }

    public List<Call> calls(ControlStage stage, String step) {
        return calls.stream().filter(c -> c.stage() == stage && c.step().name().equals(step)).toList();
    }

    @Override
    public ControlDecision evaluate(ControlStage stage, ControlStep step) {
        calls.add(new Call(stage, step));
        Deque<Function<ControlStep, ControlDecision>> queue = rules.get(key(stage, step.name()));
        Function<ControlStep, ControlDecision> rule = queue == null ? null : queue.poll();
        if (rule == null) {
            rule = sticky.get(key(stage, step.name()));
        }
        return rule == null ? ControlDecision.skipped("no scripted control", 0) : rule.apply(step);
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public String describe() {
        return "scripted test gateway";
    }

    private static String key(ControlStage stage, String step) {
        return stage.name() + ":" + step;
    }

    // ---- decision factories (shapes identical to RemoteAgentControlGateway.mapResponse) ----------------

    public static ControlDecision deny(long id, String name, String reason) {
        return evaluated(ControlAction.DENY, id, name, "deny", reason, null);
    }

    public static ControlDecision steer(long id, String name, String guidance) {
        return evaluated(ControlAction.STEER, id, name, "steer", "Pattern found", guidance);
    }

    public static ControlDecision observe(long id, String name) {
        return evaluated(ControlAction.OBSERVE, id, name, "observe", "Pattern found", null);
    }

    public static ControlDecision allow() {
        return new ControlDecision(ControlDecision.Status.EVALUATED, ControlAction.ALLOW, "no control matched", null,
                null, null, List.of(), 3, null);
    }

    private static ControlDecision evaluated(ControlAction action, long id, String name, String wireAction,
                                             String reason, String steering) {
        List<ControlMatchResult> results = new ArrayList<>();
        results.add(new ControlMatchResult(ControlMatchResult.Category.MATCH, id, name, wireAction, true, 1.0, reason,
                null, steering, "regex", "input", "preview", "exec-" + id, 0.3));
        return new ControlDecision(ControlDecision.Status.EVALUATED, action, reason, name, id, steering, results, 5,
                null);
    }
}
