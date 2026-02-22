package com.rev.triage.ragservice.controller;

import com.rev.triage.ragservice.dto.AskRequest;
import com.rev.triage.ragservice.dto.AskResponse;
import com.rev.triage.ragservice.dto.IngestTracesRequest;
import com.rev.triage.ragservice.service.IngestionService;
import com.rev.triage.ragservice.service.RagQueryService;
import com.rev.triage.ragservice.service.TelemetryIngestionService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
public class RagController {

    private final RagQueryService ragQueryService;
    private final IngestionService ingestionService;
    private final TelemetryIngestionService telemetryIngestionService;

    public RagController(RagQueryService ragQueryService,
                         IngestionService ingestionService,
                         TelemetryIngestionService telemetryIngestionService) {
        this.ragQueryService = ragQueryService;
        this.ingestionService = ingestionService;
        this.telemetryIngestionService = telemetryIngestionService;
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

    /**
     * Ingest traces from Jaeger into ChromaDB.
     * Pulls traces for all services, converts to human-readable documents, embeds them.
     *
     * curl -X POST http://localhost:8084/ingest/traces \
     *   -H "Content-Type: application/json" \
     *   -d '{"lookback":"1h","limit":20}'
     */
    @PostMapping("/ingest/traces")
    public Map<String, Object> ingestTraces(@RequestBody(required = false) IngestTracesRequest request) {
        if (request == null) {
            request = new IngestTracesRequest(null, null);
        }
        int count = telemetryIngestionService.ingestTraces(
                request.effectiveLookback(),
                request.effectiveLimit());
        return Map.of(
            "status", "completed",
            "tracesIngested", count,
            "lookback", request.effectiveLookback(),
            "limit", request.effectiveLimit()
        );
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP", "service", "rag-service");
    }
}
