package com.ruomu.xiaozhi.controller;

import com.ruomu.xiaozhi.service.ConversationHistoryService;
import org.springframework.web.bind.annotation.*;

/** 浏览器访问密钥隔离的会话接口；密钥不是正式登录账号。 */
@RestController
@RequestMapping("/api/conversations")
public class ConversationController {
    private final ConversationHistoryService history;
    public ConversationController(ConversationHistoryService history) { this.history = history; }

    @PostMapping
    public ConversationHistoryService.Conversation create(@RequestBody(required = false) CreateRequest request,
            @RequestHeader("X-Conversation-Key") String accessKey) {
        return history.create(request == null ? null : request.conversationId(), accessKey);
    }
    @GetMapping
    public ConversationHistoryService.ConversationPage list(
            @RequestParam(name = "before", required = false) String before,
            @RequestParam(name = "limit", defaultValue = "20") int limit,
            @RequestHeader("X-Conversation-Key") String accessKey) {
        return history.list(before, limit, accessKey);
    }
    @GetMapping("/{id}/messages")
    public ConversationHistoryService.HistoryPage messages(@PathVariable("id") String id,
            @RequestParam(name = "before", required = false) String before,
            @RequestParam(name = "limit", defaultValue = "20") int limit,
            @RequestHeader("X-Conversation-Key") String accessKey) {
        return history.history(id, before, limit, accessKey);
    }
    public record CreateRequest(String conversationId) {}
}
