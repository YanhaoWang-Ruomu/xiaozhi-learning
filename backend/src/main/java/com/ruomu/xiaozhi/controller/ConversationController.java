package com.ruomu.xiaozhi.controller;

import com.ruomu.xiaozhi.service.ConversationHistoryService;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;
import com.ruomu.xiaozhi.security.AccountUser;
import java.util.Map;

/** 会话仅对所属登录账号开放。 */
@RestController
@RequestMapping("/api/conversations")
public class ConversationController {
    private final ConversationHistoryService history;
    public ConversationController(ConversationHistoryService history) { this.history = history; }

    @PostMapping
    public ConversationHistoryService.Conversation create(@RequestBody(required = false) CreateRequest request,
            Authentication authentication) {
        return history.create(request == null ? null : request.conversationId(), AccountUser.require(authentication).userId());
    }
    @GetMapping
    public ConversationHistoryService.ConversationPage list(
            @RequestParam(name = "before", required = false) String before,
            @RequestParam(name = "limit", defaultValue = "20") int limit,
            Authentication authentication) {
        return history.list(before, limit, AccountUser.require(authentication).userId());
    }
    @GetMapping("/{id}/messages")
    public ConversationHistoryService.HistoryPage messages(@PathVariable("id") String id,
            @RequestParam(name = "before", required = false) String before,
            @RequestParam(name = "limit", defaultValue = "20") int limit,
            Authentication authentication) {
        return history.history(id, before, limit, AccountUser.require(authentication).userId());
    }
    @PostMapping("/import-browser")
    public Map<String, Long> importBrowser(@RequestBody ImportRequest request, Authentication authentication) {
        return Map.of("importedCount", history.importBrowserHistory(request == null ? null : request.legacyKey(), AccountUser.require(authentication).userId()));
    }
    public record ImportRequest(String legacyKey) {}
    public record CreateRequest(String conversationId) {}
}
