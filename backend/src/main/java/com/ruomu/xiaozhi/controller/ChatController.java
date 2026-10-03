package com.ruomu.xiaozhi.controller;

import com.ruomu.xiaozhi.service.ChatAssistant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ChatController {

    private final ChatAssistant assistant;

    public ChatController(ChatAssistant assistant) {
        this.assistant = assistant;
    }

    @GetMapping(value = "/api/chat", produces = "text/plain;charset=UTF-8")
    public String chat(
            @RequestParam(name = "conversationId", defaultValue = "default")
            String conversationId,
            @RequestParam(name = "message", defaultValue = "你好")
            String message) {

        return assistant.chat(conversationId, message);
    }
}