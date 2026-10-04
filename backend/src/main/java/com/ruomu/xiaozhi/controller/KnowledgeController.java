package com.ruomu.xiaozhi.controller;

import com.ruomu.xiaozhi.dto.KnowledgePreviewResponse;
import com.ruomu.xiaozhi.service.KnowledgeDocumentService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    private final KnowledgeDocumentService knowledgeDocumentService;

    public KnowledgeController(
            KnowledgeDocumentService knowledgeDocumentService
    ) {
        this.knowledgeDocumentService = knowledgeDocumentService;
    }

    @GetMapping("/chunks")
    public KnowledgePreviewResponse chunks() {
        return knowledgeDocumentService.preview();
    }
}