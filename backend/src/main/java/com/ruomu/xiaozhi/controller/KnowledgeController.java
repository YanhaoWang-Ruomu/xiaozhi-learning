package com.ruomu.xiaozhi.controller;

import com.ruomu.xiaozhi.dto.KnowledgePreviewResponse;
import com.ruomu.xiaozhi.dto.KnowledgeSearchResponse;
import com.ruomu.xiaozhi.service.KnowledgeDocumentService;
import com.ruomu.xiaozhi.service.KnowledgeSearchService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    private final KnowledgeDocumentService documentService;
    private final KnowledgeSearchService searchService;

    public KnowledgeController(
            KnowledgeDocumentService documentService,
            KnowledgeSearchService searchService
    ) {
        this.documentService = documentService;
        this.searchService = searchService;
    }

    @GetMapping("/chunks")
    public KnowledgePreviewResponse chunks() {
        return documentService.preview();
    }

    @GetMapping("/search")
    public KnowledgeSearchResponse search(
            @RequestParam(name = "query", defaultValue = "")
            String query
    ) {
        return searchService.search(query);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleStatusException(
            ResponseStatusException e
    ) {
        String message = e.getReason() == null
                ? "请求处理失败"
                : e.getReason();

        return ResponseEntity.status(e.getStatusCode()).body(
                Map.of(
                        "status", e.getStatusCode().value(),
                        "message", message
                )
        );
    }
}