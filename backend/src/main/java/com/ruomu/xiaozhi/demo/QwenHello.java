package com.ruomu.xiaozhi.demo;

import dev.langchain4j.community.model.dashscope.QwenChatModel;

public class QwenHello {

    public static void main(String[] args) {

        // 从环境变量读取密钥
        String apiKey = System.getenv("DASHSCOPE_API_KEY");

        // 如果没有读到密钥，停止执行并提示原因
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "未读取到 DASHSCOPE_API_KEY，请检查环境变量并完全重启 IDEA"
            );
        }

        // 创建调用通义千问的客户端
        QwenChatModel model = QwenChatModel.builder()
                .apiKey(apiKey)
                .modelName("qwen-plus")
                .maxTokens(256)
                .build();

        // 发送问题，等待模型返回回答
        System.out.println("正在请求通义千问……");
        String answer = model.chat("你好，请用一句话介绍你自己。");

        // 在控制台显示回答
        System.out.println("模型回复：" + answer);
    }
}