package com.ruomu.xiaozhi.controller;

import com.ruomu.xiaozhi.service.KnowledgeSearchService;
import com.ruomu.xiaozhi.service.PineconeClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
public class PineconeController {

    private final PineconeClient pinecone;
    private final KnowledgeSearchService knowledge;

    public PineconeController(
            PineconeClient pinecone,
            KnowledgeSearchService knowledge) {
        this.pinecone = pinecone;
        this.knowledge = knowledge;
    }

    @GetMapping("/api/knowledge/pinecone/status")
    public StatusResponse status() {
        var stats = pinecone.stats();

        return new StatusResponse(
                "CONNECTED",
                stats.host(),
                stats.dimension(),
                stats.totalVectorCount(),
                knowledge.knowledgeStatus(),
                "Java 后端已连接 Pinecone；knowledge 表示当前文档版本的同步状态"
        );
    }

    @PostMapping("/api/knowledge/pinecone/sync")
    public KnowledgeSearchService.SyncResponse sync() {
        return knowledge.sync();
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleError(
            ResponseStatusException e) {

        return ResponseEntity.status(e.getStatusCode()).body(Map.of(
                "status", e.getStatusCode().value(),
                "message", e.getReason() == null
                        ? "请求失败"
                        : e.getReason()
        ));
    }

    public record StatusResponse(
            String status,
            String host,
            int dimension,
            long totalVectorCount,
            KnowledgeSearchService.KnowledgeStatus knowledge,
            String message) {
    }
}