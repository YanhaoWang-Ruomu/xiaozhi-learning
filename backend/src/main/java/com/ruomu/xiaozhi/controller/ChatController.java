package com.ruomu.xiaozhi.controller;

import dev.langchain4j.community.model.dashscope.QwenChatModel;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ChatController {

    private final QwenChatModel model;

    public ChatController(QwenChatModel model) {
        this.model = model;
    }

    @GetMapping(value = "/api/chat", produces = "text/plain;charset=UTF-8")
    public String chat(
            @RequestParam(name = "message", defaultValue = "你好")
            String message) {

        return model.chat(message);
    }
}