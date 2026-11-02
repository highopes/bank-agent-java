package com.demo.bankagent.governance;

import com.demo.bankagent.control.ControlledStepRunner;
import com.demo.bankagent.control.StepCatalog;
import com.demo.bankagent.observability.BankTelemetry;
import com.demo.bankagent.observability.TraceContentPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import dev.langchain4j.service.tool.ToolExecutor;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

/**
 * Factory for the two explicit enforcement adapters: {@link GovernedChatModel} (LLM) and
 * {@link GovernedToolExecutor} (@Tool methods). Agents and the router obtain their model and tools through it, so
 * Observe and Control are applied at framework boundaries without touching the business code of the tools.
 */
@Component
public class StepGovernance {

    private final BankTelemetry telemetry;
    private final TraceContentPolicy content;
    private final ControlledStepRunner runner;
    private final StepCatalog catalog;
    private final ObjectMapper mapper;
    private final GovernedToolExecutor.ToolSideEffects sideEffects;

    public StepGovernance(BankTelemetry telemetry, TraceContentPolicy content, ControlledStepRunner runner,
                          StepCatalog catalog, ObjectMapper mapper, GovernedToolExecutor.ToolSideEffects sideEffects) {
        this.telemetry = telemetry;
        this.content = content;
        this.runner = runner;
        this.catalog = catalog;
        this.mapper = mapper;
        this.sideEffects = sideEffects;
    }

    /** Model with LLM spans and PRE/POST Control for the given step name. */
    public ChatModel governChatModel(ChatModel model, String stepName) {
        return new GovernedChatModel(unwrap(model), stepName, true, telemetry, content, runner);
    }

    /** Model with LLM spans only (no Control), e.g. the bounded answer rewrite after a STEER. */
    public ChatModel observeChatModel(ChatModel model, String stepName) {
        return new GovernedChatModel(unwrap(model), stepName, false, telemetry, content, runner);
    }

    /**
     * Tool specifications + governed executors for every @Tool method of a tools bean. The DefaultToolExecutor is
     * built exactly like LangChain4j's AiServices.tools(Object) does (wrapToolArgumentsExceptions and
     * propagateToolExecutionExceptions = true), so the existing error handlers behave as before.
     */
    public Map<ToolSpecification, ToolExecutor> governTools(Object toolsBean) {
        Class<?> type = ClassUtils.getUserClass(toolsBean);
        Map<ToolSpecification, ToolExecutor> tools = new LinkedHashMap<>();
        Method[] methods = type.getDeclaredMethods();
        Arrays.sort(methods, Comparator.comparing(Method::getName));
        for (Method method : methods) {
            if (!method.isAnnotationPresent(Tool.class)) {
                continue;
            }
            ToolSpecification spec = ToolSpecifications.toolSpecificationFrom(method);
            ToolExecutor executor = DefaultToolExecutor.builder()
                    .object(toolsBean)
                    .originalMethod(method)
                    .methodToInvoke(method)
                    .wrapToolArgumentsExceptions(true)
                    .propagateToolExecutionExceptions(true)
                    .build();
            tools.put(spec, new GovernedToolExecutor(executor, spec.name(), catalog.access(spec.name()), telemetry,
                    content, runner, mapper, sideEffects));
        }
        return tools;
    }

    private static ChatModel unwrap(ChatModel model) {
        return model instanceof GovernedChatModel governed ? governed.delegate() : model;
    }
}
