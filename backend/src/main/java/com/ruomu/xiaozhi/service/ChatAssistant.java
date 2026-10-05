package com.ruomu.xiaozhi.service;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

public interface ChatAssistant {

    @SystemMessage(fromResource = "/prompts/chat-assistant.txt")
    Result<String> chat(
            @MemoryId String conversationId,
            @UserMessage String message,
            @V("businessDateContext") String businessDateContext
    );

    @SystemMessage(fromResource = "/prompts/chat-assistant.txt")
    TokenStream stream(
            @MemoryId String conversationId,
            @UserMessage String message,
            @V("businessDateContext") String businessDateContext
    );
}