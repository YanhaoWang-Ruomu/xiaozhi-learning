package com.ruomu.xiaozhi.config;

import dev.langchain4j.community.model.dashscope.QwenEmbeddingModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class KnowledgeEmbeddingConfig {

    public static final String MODEL_NAME = "text-embedding-v3";

    @Bean("knowledgeEmbeddingModel")
    public EmbeddingModel knowledgeEmbeddingModel() {
        String apiKey = System.getenv("DASHSCOPE_API_KEY");

        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "未读取到 DASHSCOPE_API_KEY，请检查环境变量"
            );
        }

        return QwenEmbeddingModel.builder()
                .apiKey(apiKey)
                .modelName(MODEL_NAME)
                .build();
    }
}