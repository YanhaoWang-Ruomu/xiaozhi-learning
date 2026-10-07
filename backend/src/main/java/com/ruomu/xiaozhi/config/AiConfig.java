package com.ruomu.xiaozhi.config;

import com.ruomu.xiaozhi.service.ChatAssistant;
import com.ruomu.xiaozhi.service.KnowledgeRetrievalAugmentor;
import com.ruomu.xiaozhi.store.MongoChatMemoryStore;
import com.ruomu.xiaozhi.tool.AppointmentTools;
import dev.langchain4j.community.model.dashscope.QwenChatModel;
import dev.langchain4j.community.model.dashscope.QwenStreamingChatModel;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.service.AiServices;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;

@Configuration
public class AiConfig {

    @Bean
    public QwenChatModel qwenChatModel() {
        String apiKey = System.getenv("DASHSCOPE_API_KEY");

        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "未读取到 DASHSCOPE_API_KEY，请检查环境变量"
            );
        }

        return QwenChatModel.builder()
                .apiKey(apiKey)
                .modelName("qwen-plus")
                .maxTokens(512)
                .listeners(
                        List.of(
                                new TokenUsageListener(
                                        "SYNC",
                                        "qwen-plus"
                                )
                        )
                )
                .build();
    }

    @Bean
    public QwenStreamingChatModel qwenStreamingChatModel() {
        String apiKey = System.getenv("DASHSCOPE_API_KEY");

        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "未读取到 DASHSCOPE_API_KEY，请检查环境变量"
            );
        }

        return QwenStreamingChatModel.builder()
                .apiKey(apiKey)
                .modelName("qwen-plus")
                .maxTokens(512)
                .listeners(
                        List.of(
                                new TokenUsageListener(
                                        "STREAM",
                                        "qwen-plus"
                                )
                        )
                )
                .build();
    }

    @Bean("chatStreamExecutor")
    public ThreadPoolTaskExecutor chatStreamExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(8);
        executor.setThreadNamePrefix("chat-stream-");

        return executor;
    }

    @Bean
    public ChatAssistant chatAssistant(
            QwenChatModel model,
            QwenStreamingChatModel streamingModel,
            MongoChatMemoryStore memoryStore,
            AppointmentTools appointmentTools,
            KnowledgeRetrievalAugmentor knowledgeRetrievalAugmentor) {

        return AiServices.builder(ChatAssistant.class)
                .chatLanguageModel(com.ruomu.xiaozhi.observability.ObservedModels.sync(model))
                .streamingChatLanguageModel(com.ruomu.xiaozhi.observability.ObservedModels.streaming(streamingModel))
                .chatMemoryProvider(conversationId ->
                        new com.ruomu.xiaozhi.context.BudgetChatMemory(conversationId, memoryStore, 64000)
                )
                .tools(appointmentTools)
                .retrievalAugmentor(knowledgeRetrievalAugmentor)
                .build();
    }
}
