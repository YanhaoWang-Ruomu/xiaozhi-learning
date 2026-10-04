package com.ruomu.xiaozhi.config;

import com.ruomu.xiaozhi.service.ChatAssistant;
import com.ruomu.xiaozhi.service.KnowledgeRetrievalAugmentor;
import com.ruomu.xiaozhi.store.MongoChatMemoryStore;
import com.ruomu.xiaozhi.tool.AppointmentTools;
import dev.langchain4j.community.model.dashscope.QwenChatModel;
import dev.langchain4j.community.model.dashscope.QwenStreamingChatModel;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.service.AiServices;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
                .build();
    }

    @Bean
    public QwenStreamingChatModel qwenStreamingChatModel() {
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("未读取到 DASHSCOPE_API_KEY，请检查环境变量");
        }
        return QwenStreamingChatModel.builder()
                .apiKey(apiKey)
                .modelName("qwen-plus")
                .maxTokens(512)
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
                .chatLanguageModel(model)
                .streamingChatLanguageModel(streamingModel)
                .chatMemoryProvider(conversationId ->
                        MessageWindowChatMemory.builder()
                                .id(conversationId)
                                .maxMessages(20)
                                .chatMemoryStore(memoryStore)
                                .build()
                )
                .tools(appointmentTools)
                .retrievalAugmentor(knowledgeRetrievalAugmentor)
                .build();
    }
}
