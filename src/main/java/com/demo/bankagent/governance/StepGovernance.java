package com.demo.bankagent.governance;

import com.demo.bankagent.control.ControlledStepRunner;
import com.demo.bankagent.control.StepCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
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
 * Agent Control wiring for LangChain4j - used only for Control, never for Observe.
 *
 * <ul>
 *   <li>CONTROL_MODE=off: returns the original ChatModel and lets AiServices use its default
 *       {@code .tools(bean)} path, so LangChain4j runs exactly as without this class.</li>
 *   <li>CONTROL_MODE=remote-api: inserts the two enforcement adapters, {@link GovernedChatModel} (PRE/POST around
 *       a model call) and {@link GovernedToolExecutor} (PRE before / POST after the real {@code @Tool} method).</li>
 * </ul>
 * The adapters create no spans: Observe comes from the OpenTelemetry Java agent and the LangChain4j listeners,
 * and the adapters run INSIDE the LLM / tool span those listeners open, so Control spans nest under the step.
 */
@Component
public class StepGovernance {

    private final ControlledStepRunner runner;
    private final StepCatalog catalog;
    private final ObjectMapper mapper;
    private final GovernedToolExecutor.ToolSideEffects sideEffects;

    public StepGovernance(ControlledStepRunner runner, StepCatalog catalog, ObjectMapper mapper,
                          GovernedToolExecutor.ToolSideEffects sideEffects) {
        this.runner = runner;
        this.catalog = catalog;
        this.mapper = mapper;
        this.sideEffects = sideEffects;
    }

    /** The model to use for a Control step: unchanged when Control is off. */
    public ChatModel chatModel(ChatModel model, String stepName) {
        return runner.enabled() ? new GovernedChatModel(model, stepName, runner) : model;
    }

    /** Registers the tools bean: the LangChain4j default when Control is off, PRE/POST-gated executors when on. */
    public <T> AiServices<T> tools(AiServices<T> builder, Object toolsBean) {
        return runner.enabled() ? builder.tools(governTools(toolsBean)) : builder.tools(toolsBean);
    }

    /**
     * Tool specifications + gated executors for every @Tool method, built with LangChain4j's public API exactly
     * like AiServices.tools(Object) does (wrapToolArgumentsExceptions and propagateToolExecutionExceptions = true),
     * so the existing error handlers behave as on the default path.
     */
    Map<ToolSpecification, ToolExecutor> governTools(Object toolsBean) {
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
            tools.put(spec, new GovernedToolExecutor(executor, spec.name(), catalog.access(spec.name()), runner,
                    mapper, sideEffects));
        }
        return tools;
    }
}
