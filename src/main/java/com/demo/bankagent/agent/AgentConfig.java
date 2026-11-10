package com.demo.bankagent.agent;

import com.demo.bankagent.config.BankAgentProperties;
import com.demo.bankagent.control.StepCatalog;
import com.demo.bankagent.governance.StepGovernance;
import com.demo.bankagent.llm.SecretMasker;
import com.demo.bankagent.observability.LangChain4jOtelBridge;
import com.demo.bankagent.service.BankingException;
import com.demo.bankagent.tool.AccountTools;
import com.demo.bankagent.tool.PaymentTools;
import com.demo.bankagent.tool.TransactionTools;
import com.demo.bankagent.trace.ExecutionTrace;
import com.demo.bankagent.trace.ToolCallTrace;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.tool.ToolErrorContext;
import dev.langchain4j.service.tool.ToolErrorHandlerResult;
import dev.langchain4j.service.tool.ToolExecution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the three domain agents as LangChain4j AI services. The model decides when to call the
 * @Tool methods; tool executions are recorded into the request's ExecutionTrace.
 *
 * <p>Instrumentation is registered here once for every agent, with LangChain4j's own extension points:
 * <ul>
 *   <li>Observe: {@code registerListeners} (agent spans) and {@code beforeToolExecution} / {@code afterToolExecution}
 *       (tool spans) feed {@link LangChain4jOtelBridge}; LLM spans come from the same bridge registered as a
 *       ChatModelListener in LlmConfig. With no OpenTelemetry Java agent attached they do nothing.</li>
 *   <li>Control: {@link StepGovernance} returns the original model and tools when CONTROL_MODE=off, and adds the
 *       PRE/POST enforcement adapters only when CONTROL_MODE=remote-api.</li>
 * </ul>
 * No @Tool method, service or repository contains instrumentation code.
 */
@Configuration
public class AgentConfig {

    private static final Logger log = LoggerFactory.getLogger(AgentConfig.class);
    private static final int MAX_ARGUMENT_CHARS = 300;

    private final StepGovernance control;
    private final LangChain4jOtelBridge otel;

    public AgentConfig(StepGovernance control, LangChain4jOtelBridge otel) {
        this.control = control;
        this.otel = otel;
    }

    @Bean
    public AccountAgent accountAgent(ChatModel chatModel, AccountTools tools, AgentMemoryStores memory,
                                     AgentPrompts prompts, BankAgentProperties properties) {
        return build(AccountAgent.class, AgentType.ACCOUNT, chatModel, tools, memory, prompts, properties);
    }

    @Bean
    public TransactionsAgent transactionsAgent(ChatModel chatModel, TransactionTools tools, AgentMemoryStores memory,
                                               AgentPrompts prompts, BankAgentProperties properties) {
        return build(TransactionsAgent.class, AgentType.TRANSACTIONS, chatModel, tools, memory, prompts, properties);
    }

    @Bean
    public PaymentsAgent paymentsAgent(ChatModel chatModel, PaymentTools tools, AgentMemoryStores memory,
                                       AgentPrompts prompts, BankAgentProperties properties) {
        return build(PaymentsAgent.class, AgentType.PAYMENTS, chatModel, tools, memory, prompts, properties);
    }

    private <T> T build(Class<T> type, AgentType agent, ChatModel chatModel, Object tools,
                        AgentMemoryStores memory, AgentPrompts prompts, BankAgentProperties properties) {
        AiServices<T> builder = AiServices.builder(type)
                .chatModel(control.chatModel(chatModel, StepCatalog.agentStep(agent)))
                .chatMemoryProvider(memory.provider(agent))
                .systemMessageProvider(memoryId -> prompts.systemPrompt(agent))
                .maxSequentialToolsInvocations(properties.agent().maxToolInvocations())
                .registerListeners(otel.aiServiceListeners())
                .beforeToolExecution(otel::toolStarted)
                .afterToolExecution(execution -> {
                    recordToolExecution(execution);
                    otel.toolFinished(execution);
                })
                .toolExecutionErrorHandler(AgentConfig::toolError)
                .toolArgumentsErrorHandler(AgentConfig::argumentError);
        return control.tools(builder, tools).build();
    }

    static void recordToolExecution(ToolExecution execution) {
        String tool = execution.request().name();
        long ms = execution.duration() == null ? -1 : execution.duration().toMillis();
        boolean success = !execution.hasFailed();
        String args = execution.request().arguments();
        String safeArgs = args == null ? "" : SecretMasker.mask(args.length() > MAX_ARGUMENT_CHARS
                ? args.substring(0, MAX_ARGUMENT_CHARS) + "..." : args);
        ExecutionTrace.current().ifPresent(t -> t.addToolCall(
                new ToolCallTrace(t.currentCaller(), tool, safeArgs, ms, success)));
        log.info("Tool executed agent={} tool={} success={} durationMs={}",
                ExecutionTrace.current().map(ExecutionTrace::currentCaller).orElse("unknown"), tool, success, ms);
    }

    static ToolErrorHandlerResult toolError(Throwable error, ToolErrorContext context) {
        BankingException business = findBusinessError(error);
        String tool = context.toolExecutionRequest() == null ? "unknown" : context.toolExecutionRequest().name();
        if (business != null) {
            log.info("Tool {} returned business error {}", tool, business.getCode());
            return ToolErrorHandlerResult.text("{\"error\":\"" + business.getCode().name() + "\",\"message\":\""
                    + jsonEscape(business.getMessage()) + "\"}");
        }
        log.warn("Tool {} failed unexpectedly: {}", tool, SecretMasker.mask(String.valueOf(error.getMessage())));
        return ToolErrorHandlerResult.text("{\"error\":\"INTERNAL_ERROR\",\"message\":\"The tool failed unexpectedly. "
                + "Tell the user the request could not be completed and suggest retrying.\"}");
    }

    static ToolErrorHandlerResult argumentError(Throwable error, ToolErrorContext context) {
        String tool = context.toolExecutionRequest() == null ? "unknown" : context.toolExecutionRequest().name();
        log.info("Tool {} received invalid arguments: {}", tool, SecretMasker.mask(String.valueOf(error.getMessage())));
        return ToolErrorHandlerResult.text("{\"error\":\"INVALID_ARGUMENTS\",\"message\":\"Invalid arguments for "
                + tool + ". Check the parameter types and formats and try again.\"}");
    }

    private static BankingException findBusinessError(Throwable error) {
        Throwable current = error;
        int depth = 0;
        while (current != null && depth++ < 10) {
            if (current instanceof BankingException be) {
                return be;
            }
            current = current.getCause();
        }
        return null;
    }

    private static String jsonEscape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }
}
