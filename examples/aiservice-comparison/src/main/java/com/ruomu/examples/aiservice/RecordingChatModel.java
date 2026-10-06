package com.ruomu.examples.aiservice;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.ArrayList;
import java.util.List;

/** 本地确定性替身：记录框架交给模型的请求，不访问网络，不具备真实语言理解能力。 */
public class RecordingChatModel implements ChatLanguageModel {
    private final List<ChatRequest> requests = new ArrayList<>();

    @Override public synchronized ChatResponse doChat(ChatRequest request) {
        requests.add(request);
        var messages = request.messages();
        var last = messages.get(messages.size() - 1);
        AiMessage answer;
        if (last instanceof ToolExecutionResultMessage result) {
            answer = AiMessage.from("TOOL_RESULT=" + result.text());
        } else {
            UserMessage user = (UserMessage) last;
            if ("查询演示规则".equals(user.singleText())) {
                // 模拟模型请求调用工具；工具的实际执行由 AI Services 负责。
                var call = ToolExecutionRequest.builder().id("demo-rule-call")
                        .name("readDemoRule").arguments("{}").build();
                answer = AiMessage.from(call);
            } else {
                long count = messages.stream().filter(UserMessage.class::isInstance).count();
                answer = AiMessage.from("ECHO=" + user.singleText() + ";USER_MESSAGES=" + count);
            }
        }
        return ChatResponse.builder().aiMessage(answer).build();
    }
    public synchronized List<ChatRequest> requests() { return List.copyOf(requests); }
    public synchronized void clear() { requests.clear(); }
}
