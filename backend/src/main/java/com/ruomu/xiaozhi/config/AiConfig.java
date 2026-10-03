package com.ruomu.xiaozhi.config;

import com.ruomu.xiaozhi.service.ChatAssistant;
import dev.langchain4j.community.model.dashscope.QwenChatModel;
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
                .maxTokens(256)
                .build();
    }

    @Bean
    public ChatAssistant chatAssistant(QwenChatModel model) {
        return AiServices.builder(ChatAssistant.class)
                .chatLanguageModel(model)
                .chatMemoryProvider(conversationId ->
                        MessageWindowChatMemory.builder()
                                .id(conversationId)
                                .maxMessages(20)
                                .build()
                )
                .build();
    }
}