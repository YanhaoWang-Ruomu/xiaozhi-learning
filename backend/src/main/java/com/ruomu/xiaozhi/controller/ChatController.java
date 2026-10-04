package com.ruomu.xiaozhi.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruomu.xiaozhi.dto.AppointmentDraftResponse;
import com.ruomu.xiaozhi.dto.ChatRequest;
import com.ruomu.xiaozhi.dto.ChatResponse;
import com.ruomu.xiaozhi.service.AppointmentDraftService;
import com.ruomu.xiaozhi.service.ChatAssistant;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.tool.ToolExecution;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
public class ChatController {

    private final ChatAssistant assistant;
    private final AppointmentDraftService draftService;
    private final ObjectMapper objectMapper;

    public ChatController(
            ChatAssistant assistant,
            AppointmentDraftService draftService,
            ObjectMapper objectMapper) {

        this.assistant = assistant;
        this.draftService = draftService;
        this.objectMapper = objectMapper;
    }

    // 新网页使用此接口，消息通过 JSON 请求体发送。
    @PostMapping(
            value = "/api/chat",
            consumes = "application/json",
            produces = "application/json;charset=UTF-8"
    )
    public ChatResponse chat(@RequestBody ChatRequest request) {

        validate(request);

        Result<String> result = assistant.chat(
                request.conversationId().strip(),
                request.message().strip()
        );

        return new ChatResponse(
                result.content(),
                collectDrafts(result),
                collectSources(result)
        );
    }

    // 保留之前的浏览器地址测试方式，仍返回纯文本。
    @GetMapping(
            value = "/api/chat",
            produces = "text/plain;charset=UTF-8"
    )
    public String chatText(
            @RequestParam(
                    name = "conversationId",
                    defaultValue = "default"
            )
            String conversationId,

            @RequestParam(
                    name = "message",
                    defaultValue = "你好"
            )
            String message) {

        ChatRequest request = new ChatRequest(conversationId, message);
        validate(request);

        return assistant.chat(
                conversationId.strip(),
                message.strip()
        ).content();
    }

    private void validate(ChatRequest request) {

        if (request == null
                || request.conversationId() == null
                || request.conversationId().isBlank()
                || request.message() == null
                || request.message().isBlank()) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "会话编号和消息不能为空"
            );
        }

        if (request.conversationId().length() > 128
                || request.message().length() > 2000) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "会话编号不能超过128个字符，消息不能超过2000个字符"
            );
        }
    }

    // 只读取本轮实际检索结果，不从模型回答中解析“来源”。
    private List<ChatResponse.Source> collectSources(Result<String> result) {
        if (result.sources() == null || result.sources().isEmpty()) {
            return List.of();
        }

        List<ChatResponse.Source> sources = new ArrayList<>();

        for (Content content : result.sources()) {
            if (content == null || content.textSegment() == null) {
                continue;
            }

            var segment = content.textSegment();
            var metadata = segment.metadata().toMap();
            Object sourceValue = metadata.get("source");
            Object indexValue = metadata.get("index");

            // 当前知识片段必须携带文件名、非负整数编号和原文。
            if (!(sourceValue instanceof String source) || source.isBlank()
                    || !(indexValue instanceof Integer index) || index < 0
                    || segment.text() == null || segment.text().isBlank()) {
                continue;
            }

            sources.add(new ChatResponse.Source(
                    source,
                    index,
                    segment.text()
            ));
        }

        return List.copyOf(sources);
    }

    private List<AppointmentDraftResponse> collectDrafts(
            Result<String> result) {

        if (result.toolExecutions() == null) {
            return List.of();
        }

        Map<String, AppointmentDraftResponse> drafts =
                new LinkedHashMap<>();

        for (ToolExecution execution : result.toolExecutions()) {

            if (!"createAppointmentDraft".equals(
                    execution.request().name())) {
                continue;
            }

            JsonNode toolResult = parseToolResult(execution.result());

            if (!"PENDING_CONFIRMATION".equals(
                    toolResult.path("status").asText())) {
                continue;
            }

            String draftId = toolResult
                    .path("draft")
                    .path("draftId")
                    .asText("");

            if (draftId.isBlank()) {
                throw new IllegalStateException(
                        "工具报告草稿生成成功，但没有返回草稿编号"
                );
            }

            // 编号来自真实工具执行结果，详情重新从数据库读取。
            AppointmentDraftResponse draft =
                    draftService.findById(draftId);

            drafts.put(draftId, draft);
        }

        return List.copyOf(drafts.values());
    }

    private JsonNode parseToolResult(String json) {

        try {
            JsonNode node = objectMapper.readTree(json);

            if (node == null || !node.isObject()) {
                throw new IllegalStateException("预约工具返回格式异常");
            }

            return node;

        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "无法解析预约工具执行结果",
                    exception
            );
        }
    }
}