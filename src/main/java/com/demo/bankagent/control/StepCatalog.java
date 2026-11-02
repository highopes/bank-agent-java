package com.demo.bankagent.control;

import com.demo.bankagent.agent.AgentType;
import com.demo.bankagent.tool.AccountTools;
import com.demo.bankagent.tool.PaymentTools;
import com.demo.bankagent.tool.TransactionTools;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The stable step names that can be selected in Console controls (scope.step_names) and that are registered with
 * Agent Control at startup (initAgent). Tool names come from the real @Tool methods, so the catalog cannot drift
 * from the code.
 */
@Component
public class StepCatalog {

    /** The whole chat turn: PRE = the user's message, POST = the final answer shown to the user. */
    public static final String BANKING_ASSISTANT = "banking-assistant";
    /** Model calls of the Supervisor's intent router. */
    public static final String SUPERVISOR_ROUTER = "supervisor-router";
    /** The UI confirmation that really debits the (simulated) account; not reachable from the LLM. */
    public static final String PAYMENT_CONFIRM = "payment.confirm";

    private static final Set<String> WRITE_STEPS = Set.of("createTransferDraft", "cancelPendingTransfer",
            PAYMENT_CONFIRM);

    public record StepDescriptor(String type, String name, String description, StepAccess access) {
    }

    private final List<StepDescriptor> steps;

    public StepCatalog() {
        List<StepDescriptor> list = new ArrayList<>();
        list.add(new StepDescriptor(ControlStep.TYPE_LLM, BANKING_ASSISTANT,
                "Whole chat turn: pre = user message, post = final answer", StepAccess.READ));
        list.add(new StepDescriptor(ControlStep.TYPE_LLM, SUPERVISOR_ROUTER,
                "Supervisor intent routing model call (JSON output)", StepAccess.READ));
        for (AgentType agent : AgentType.values()) {
            list.add(new StepDescriptor(ControlStep.TYPE_LLM, agentStep(agent),
                    agent.displayName() + " model calls: pre = new user/task message, post = text answer",
                    StepAccess.READ));
        }
        for (Class<?> tools : List.of(AccountTools.class, TransactionTools.class, PaymentTools.class)) {
            for (ToolSpecification spec : ToolSpecifications.toolSpecificationsFrom(tools)) {
                list.add(new StepDescriptor(ControlStep.TYPE_TOOL, spec.name(), shorten(spec.description()),
                        access(spec.name())));
            }
        }
        list.add(new StepDescriptor(ControlStep.TYPE_TOOL, PAYMENT_CONFIRM,
                "UI confirmation of a pending transfer draft (executes the simulated debit)", StepAccess.WRITE));
        this.steps = List.copyOf(list);
    }

    public static String agentStep(AgentType agent) {
        return agent.name().toLowerCase(Locale.ROOT) + "-agent";
    }

    public List<StepDescriptor> steps() {
        return steps;
    }

    public StepAccess access(String stepName) {
        return WRITE_STEPS.contains(stepName) ? StepAccess.WRITE : StepAccess.READ;
    }

    private static String shorten(String text) {
        if (text == null) {
            return null;
        }
        return text.length() > 200 ? text.substring(0, 200) : text;
    }
}
