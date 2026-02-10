package com.rev.triage.ragservice.controller;

import com.rev.triage.ragservice.dto.AskRequest;
import com.rev.triage.ragservice.dto.AskResponse;
import com.rev.triage.ragservice.service.IngestionService;
import com.rev.triage.ragservice.service.RagQueryService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
public class RagController {

    private final RagQueryService ragQueryService;
    private final IngestionService ingestionService;

    public RagController(RagQueryService ragQueryService,
                         IngestionService ingestionService) {
        this.ragQueryService = ragQueryService;
        this.ingestionService = ingestionService;
    }

    @PostMapping("/ask")
    public AskResponse ask(@RequestBody AskRequest request) {
        String answer = ragQueryService.ask(request.question());
        return new AskResponse(request.question(), answer);
    }

    @PostMapping("/ingest")
    public Map<String, Object> ingest() {
        int count = ingestionService.ingestDocuments();
        return Map.of(
            "status", "completed",
            "documentsIngested", count
        );
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP", "service", "rag-service");
    }
}
