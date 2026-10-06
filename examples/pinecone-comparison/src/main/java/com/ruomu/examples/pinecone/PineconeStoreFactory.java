package com.ruomu.examples.pinecone;

import dev.langchain4j.store.embedding.pinecone.PineconeEmbeddingStore;

/** 真实LangChain4j适配器工厂；测试在SDK构造边界替换网络，不替换此适配器。 */
public final class PineconeStoreFactory {
    private PineconeStoreFactory() {}
    public static PineconeEmbeddingStore create(String apiKey, String indexName, String namespace) {
        if (apiKey == null || apiKey.isBlank() || indexName == null || indexName.isBlank()
                || namespace == null || namespace.isBlank()) throw new IllegalArgumentException("必须明确配置密钥、索引名称和命名空间");
        return PineconeEmbeddingStore.builder()
                .apiKey(apiKey)
                .index(indexName) // 索引名称；不同于主项目REST客户端接收的index host。
                .nameSpace(namespace)
                .metadataTextKey("text") // 与当前知识文档正文键一致。
                .build(); // 不提供createIndex，避免创建索引。
    }
}
