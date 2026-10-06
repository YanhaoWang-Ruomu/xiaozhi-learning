package com.ruomu.examples.aiservice;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.AiServices;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
public class LabConfiguration {
    @Bean public RecordingChatModel labModel() { return new RecordingChatModel(); }

    // 同时存在两个模型 Bean，显式装配仍准确选择 labModel。
    @Bean public ChatLanguageModel unusedModel() {
        return new ChatLanguageModel() {
            @Override public ChatResponse doChat(ChatRequest request) {
                throw new IllegalStateException("不应该调用 unusedModel");
            }
        };
    }
    @Bean public ChatMemoryProvider manualMemory() { return memoryProvider(); }
    @Bean public ChatMemoryProvider declarativeMemory() { return memoryProvider(); }
    private ChatMemoryProvider memoryProvider() {
        // 两套独立内存窗口，保证相同输入可以公平对照；不连接主项目MongoDB。
        return id -> MessageWindowChatMemory.builder().id(id).maxMessages(8).build();
    }
    @Bean public LabTools labTools() { return new LabTools(); }
    @Bean public UnrelatedTools unrelatedTools() { return new UnrelatedTools(); }

    @Bean public ManualAssistant manualAssistant(RecordingChatModel model,
            @Qualifier("manualMemory") ChatMemoryProvider memory, LabTools tools) {
        return AiServices.builder(ManualAssistant.class)
                .chatLanguageModel(model)
                .chatMemoryProvider(memory)
                .tools(tools)
                .build();
    }
    public static class LabTools {
        private final AtomicInteger calls = new AtomicInteger();
        @Tool("返回虚构教学规则；不会创建或取消任何预约")
        public String readDemoRule(@ToolMemoryId String conversationId) {
            calls.incrementAndGet();
            return "DEMO_ONLY;MEMORY_ID=" + conversationId + ";MANUAL_CONFIRMATION_REQUIRED";
        }
        public int calls() { return calls.get(); }
    }
    public static class UnrelatedTools {
        @Tool("装配排除测试，禁止向本示例助手开放")
        public String unrelatedOperation() {
            throw new IllegalStateException("不应装配此工具");
        }
    }
}
