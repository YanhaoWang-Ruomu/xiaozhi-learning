package com.ruomu.xiaozhi.controller;
import com.ruomu.xiaozhi.service.HybridKnowledgeService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
@RestController
public class RetrievalExperimentController {
    private final HybridKnowledgeService search;
    public RetrievalExperimentController(HybridKnowledgeService search){this.search=search;}
    @GetMapping("/api/knowledge/compare")
    public HybridKnowledgeService.Result compare(@RequestParam String query,@RequestParam(defaultValue="hybrid") String mode){
        if(!java.util.Set.of("vector","hybrid","llm","dedicated").contains(mode)||query.isBlank()||query.length()>500)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"检索参数无效");
        return search.search(query,mode);
    }
}
