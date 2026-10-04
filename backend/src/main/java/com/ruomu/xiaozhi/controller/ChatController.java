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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
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

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private final Set<String> activeConversations = ConcurrentHashMap.newKeySet();
    private final Semaphore capacity = new Semaphore(8);
    private final Executor streamExecutor;

    private final ChatAssistant assistant;
    private final AppointmentDraftService draftService;
    private final ObjectMapper objectMapper;

    public ChatController(
            ChatAssistant assistant,
            AppointmentDraftService draftService,
            ObjectMapper objectMapper,
            @Qualifier("chatStreamExecutor") Executor streamExecutor) {

        this.assistant = assistant;
        this.draftService = draftService;
        this.objectMapper = objectMapper;
        this.streamExecutor = streamExecutor;
    }

    // 新网页使用此接口，消息通过 JSON 请求体发送。
    @PostMapping(
            value = "/api/chat",
            consumes = "application/json",
            produces = "application/json;charset=UTF-8"
    )
    public ChatResponse chat(@RequestBody ChatRequest request) {

        validate(request);

        try (Lease ignored = acquire(request.conversationId().strip())) {
            Result<String> result = assistant.chat(
                    request.conversationId().strip(), request.message().strip());
            return new ChatResponse(result.content(),
                    collectDrafts(result.toolExecutions()), collectSources(result.sources()));
        }
    }

    @PostMapping(value = "/api/chat/stream", consumes = "application/json",
            produces = "text/event-stream;charset=UTF-8")
    public ResponseEntity<SseEmitter> stream(@RequestBody ChatRequest request) {
        validate(request);
        String id = request.conversationId().strip();
        Lease lease = acquire(id);
        SseEmitter emitter = new SseEmitter(180_000L);
        StreamState state = new StreamState(emitter, lease);
        emitter.onCompletion(state::disconnected);
        emitter.onError(error -> state.disconnected());
        emitter.onTimeout(state::timedOut);
        try {
            streamExecutor.execute(() -> {
                try {
                    if (state.closed.get()) {
                        lease.close();
                        return;
                    }
                    state.send("status", Map.of("message", "正在检索资料并准备回复……"));
                    var tokens = assistant.stream(id, request.message().strip());
                    if (state.closed.get()) {
                        lease.close();
                        return;
                    }
                    tokens.onRetrieved(contents -> {
                                state.sources = collectSources(contents);
                                state.send("sources", Map.of("sources", state.sources));
                            })
                            .onPartialResponse(token -> {
                                if (token != null && !token.isEmpty()) {
                                    state.send("token", Map.of("text", token));
                                }
                            })
                            .onToolExecuted(execution -> {
                                state.tools.add(execution);
                                state.send("status", Map.of("message",
                                        "演示工具已执行，正在整理回复……"));
                            })
                            .onCompleteResponse(response -> {
                                try {
                                    if (response.finishReason() == dev.langchain4j.model.output.FinishReason.LENGTH) {
                                        state.failMessage("回复达到长度限制，内容不完整。请先核实草稿，再缩短问题。");
                                        return;
                                    }
                                    String reply = response.aiMessage().text();
                                    if (reply == null || reply.isBlank()) {
                                        throw new IllegalStateException("模型未返回文本");
                                    }
                                    state.done(new ChatResponse(reply,
                                            collectDrafts(state.tools), state.sources));
                                } catch (RuntimeException e) {
                                    state.failed(e);
                                }
                            })
                            .onError(state::failed)
                            .start();
                } catch (RuntimeException e) {
                    state.failed(e);
                }
            });
        } catch (RuntimeException e) {
            lease.close();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "聊天任务暂时无法启动，请稍后再试");
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/event-stream;charset=UTF-8"))
                .header("Cache-Control", "no-cache")
                .header("X-Accel-Buffering", "no").body(emitter);
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

        try (Lease ignored = acquire(conversationId.strip())) {
            return assistant.chat(conversationId.strip(), message.strip()).content();
        }
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
    private List<ChatResponse.Source> collectSources(List<Content> contents) {
        if (contents == null || contents.isEmpty()) {
            return List.of();
        }

        List<ChatResponse.Source> sources = new ArrayList<>();

        for (Content content : contents) {
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

            sources.add(new ChatResponse.Source(source, index, segment.text()));
        }

        return List.copyOf(sources);
    }

    private List<AppointmentDraftResponse> collectDrafts(
            List<ToolExecution> executions) {

        if (executions == null) {
            return List.of();
        }

        Map<String, AppointmentDraftResponse> drafts =
                new LinkedHashMap<>();

        for (ToolExecution execution : executions) {

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

    private Lease acquire(String id) {
        if (!activeConversations.add(id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "当前会话的上一条请求仍在处理中，请稍后再试；请勿重复生成草稿");
        }
        if (!capacity.tryAcquire()) {
            activeConversations.remove(id);
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "当前处理中的聊天较多，请稍后再试");
        }
        return new Lease(id);
    }

    private final class Lease implements AutoCloseable {
        private final String id;
        private final AtomicBoolean released = new AtomicBoolean();
        private Lease(String id) { this.id = id; }
        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                activeConversations.remove(id);
                capacity.release();
            }
        }
    }

    private final class StreamState {
        private final SseEmitter emitter;
        private final Lease lease;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean finished = new AtomicBoolean();
        private final List<ToolExecution> tools = new CopyOnWriteArrayList<>();
        private volatile List<ChatResponse.Source> sources = List.of();

        private StreamState(SseEmitter emitter, Lease lease) {
            this.emitter = emitter;
            this.lease = lease;
        }

        private synchronized void send(String event, Object data) {
            if (closed.get()) return;
            try {
                emitter.send(SseEmitter.event().name(event)
                        .data(data, MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException e) {
                // 客户端断开不等于模型或工具已经停止，不能在此释放会话占用。
                closed.set(true);
            }
        }

        private void disconnected() { closed.set(true); }

        private synchronized void timedOut() {
            // Spring 6.1 超时回调发生时已禁止继续 send。
            // 结束连接；前端因未收到 done，将本轮标记为未完成。
            closed.set(true);
            emitter.complete();
            // 模型可能仍在执行工具，等待真实完成或错误回调再释放会话。
        }

        private void closeTransport() {
            if (!closed.getAndSet(true)) emitter.complete();
        }

        private synchronized void done(ChatResponse response) {
            if (!finished.compareAndSet(false, true)) return;
            lease.close();
            send("done", response);
            closeTransport();
        }

        private void failed(Throwable error) {
            log.warn("流式聊天失败，异常类型：{}", error.getClass().getSimpleName());
            failMessage("本轮回复未完整取得，请先同步当前会话草稿核实；请求不会自动重发。");
        }

        private synchronized void failMessage(String message) {
            if (!finished.compareAndSet(false, true)) return;
            lease.close();
            send("failed", Map.of("message", message));
            closeTransport();
        }
    }
}
