package com.demo.bankagent.support;

import com.demo.bankagent.llm.TraceChatModelListener;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * Offline stand-in for the OpenAI chat model. Tests script the Supervisor routing replies and the domain agent
 * replies (tool calls and final texts). Real LangChain4j AI services, real tools and real services are used;
 * only the network call is replaced. Every request is recorded for assertions.
 */
public class ScriptedChatModel implements ChatModel {

    public static final String MODEL_NAME = "scripted-test-model";

    private final Deque<Function<ChatRequest, AiMessage>> routerReplies = new ConcurrentLinkedDeque<>();
    private final Deque<Function<ChatRequest, AiMessage>> agentReplies = new ConcurrentLinkedDeque<>();
    private final List<ChatRequest> requests = new CopyOnWriteArrayList<>();

    public void reset() {
        routerReplies.clear();
        agentReplies.clear();
        requests.clear();
    }

    public ScriptedChatModel route(String routingJson) {
        routerReplies.add(request -> AiMessage.from(routingJson));
        return this;
    }

    public ScriptedChatModel routeTo(String agent, String task) {
        return route("{\"intent\":\"AGENTS\",\"steps\":[{\"agent\":\"" + agent + "\",\"task\":\"" + task
                + "\"}],\"reply\":\"\"}");
    }

    public ScriptedChatModel toolCall(String tool, String argumentsJson) {
        agentReplies.add(request -> AiMessage.from(ToolExecutionRequest.builder()
                .id("call-" + tool + "-" + requests.size()).name(tool).arguments(argumentsJson).build()));
        return this;
    }

    public ScriptedChatModel answer(String text) {
        agentReplies.add(request -> AiMessage.from(text));
        return this;
    }

    public ScriptedChatModel answer(Function<ChatRequest, String> text) {
        agentReplies.add(request -> AiMessage.from(text.apply(request)));
        return this;
    }

    public List<ChatRequest> requests() {
        return List.copyOf(requests);
    }

    public List<ChatRequest> agentRequests() {
        return requests.stream().filter(r -> !isRouter(r)).toList();
    }

    public boolean allConsumed() {
        return routerReplies.isEmpty() && agentReplies.isEmpty();
    }

    public static boolean isRouter(ChatRequest request) {
        return request.responseFormat() != null && request.responseFormat().type() == ResponseFormatType.JSON;
    }

    /** Text of the latest tool result in the request, or null. */
    public static String lastToolResult(ChatRequest request) {
        List<ChatMessage> messages = request.messages();
        ChatMessage last = messages.get(messages.size() - 1);
        return last instanceof ToolExecutionResultMessage result ? result.text() : null;
    }

    /** All user message texts in the request (the agent's memory plus the current input). */
    public static List<String> userTexts(ChatRequest request) {
        List<String> texts = new ArrayList<>();
        for (ChatMessage message : request.messages()) {
            if (message instanceof UserMessage user) {
                texts.add(user.singleText());
            }
        }
        return texts;
    }

    @Override
    public ChatResponse doChat(ChatRequest request) {
        requests.add(request);
        Function<ChatRequest, AiMessage> reply = isRouter(request) ? routerReplies.poll() : agentReplies.poll();
        if (reply == null) {
            throw new IllegalStateException("No scripted reply left for " + (isRouter(request) ? "router" : "agent"));
        }
        AiMessage message = reply.apply(request);
        return ChatResponse.builder()
                .aiMessage(message)
                .modelName(MODEL_NAME)
                .tokenUsage(new TokenUsage(11, 7))
                .finishReason(message.hasToolExecutionRequests() ? FinishReason.TOOL_EXECUTION : FinishReason.STOP)
                .build();
    }

    @Override
    public List<ChatModelListener> listeners() {
        return List.of(new TraceChatModelListener());
    }
}
