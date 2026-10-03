package com.ruomu.xiaozhi.service;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;

public interface ChatAssistant {

    String chat(
            @MemoryId String conversationId,
            @UserMessage String message
    );
}