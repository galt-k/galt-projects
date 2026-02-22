package com.rev.triage.ragservice.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.rev.triage.ragservice.client.JaegerClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Converts Jaeger traces into Spring AI Documents and embeds them into ChromaDB.
 *
 * Migrated from LangChain4j → Spring AI:
 *   - LangChain4j Document.from(text, Metadata) → Spring AI new Document(text, Map)
 *   - LangChain4j Metadata                      → Map<String, Object>
 *   - EmbeddingStoreIngestor                     → TokenTextSplitter + VectorStore.add()
 *   - embeddingStore.removeAll(filter)            → vectorStore.delete(filterExpression)
 *
 * Chunking strategy:
 *   - Each TRACE becomes one Document (not each span)
 *   - The document text is a human-readable narrative of the trace
 *   - Metadata includes: traceId, root service, duration, error status, span count
 *   - Documents are then split with TokenTextSplitter for embedding
 *
 * Why per-trace and not per-span?
 *   A span alone lacks context. "GET /products/1 took 15ms" is meaningless.
 *   A trace tells a story: "Order creation took 400ms — 50ms in validation,
 *   15ms per product lookup, 170ms in payment processing."
 *   The RAG pipeline needs stories, not isolated facts.
 */
@Service
public class TelemetryIngestionService {

    private static final Logger log = LoggerFactory.getLogger(TelemetryIngestionService.class);
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    private final JaegerClient jaegerClient;
    private final VectorStore vectorStore;

    /** In-memory set of trace IDs already embedded in this JVM session (fast dedup). */
    private final Set<String> knownTraceIds = ConcurrentHashMap.newKeySet();

    public TelemetryIngestionService(JaegerClient jaegerClient,
                                      VectorStore vectorStore) {
        this.jaegerClient = jaegerClient;
        this.vectorStore = vectorStore;
    }

    /**
     * Fetch traces from Jaeger and ingest them into ChromaDB.
     *
     * @param lookback  how far back to look (e.g., "1h", "6h", "1d")
     * @param limit     max traces per service
     * @return number of trace documents ingested
     */
    public int ingestTraces(String lookback, int limit) {
        log.info("Starting telemetry ingestion (lookback: {}, limit: {})", lookback, limit);

        List<JsonNode> traces = jaegerClient.getAllTraces(lookback, limit);
        if (traces.isEmpty()) {
            log.warn("No traces found in Jaeger");
            return 0;
        }

        List<Document> documents = new ArrayList<>();
        int skippedDuplicates = 0;
        for (JsonNode trace : traces) {
            try {
                Document doc = traceToDocument(trace);
                if (doc != null) {
                    String traceId = (String) doc.getMetadata().get("traceId");
                    if (traceId != null && !traceId.equals("unknown")) {
                        // Fast path: skip if already embedded in this JVM session
                        if (knownTraceIds.contains(traceId)) {
                            skippedDuplicates++;
                            continue;
                        }
                        // Remove any pre-existing chunks for this trace (handles restarts)
                        deduplicateTrace(traceId);
                        knownTraceIds.add(traceId);
                    }
                    documents.add(doc);
                }
            } catch (Exception e) {
                log.error("Failed to convert trace to document", e);
            }
        }
        if (skippedDuplicates > 0) {
            log.info("Skipped {} duplicate traces (already embedded)", skippedDuplicates);
        }

        if (documents.isEmpty()) {
            log.warn("No documents generated from traces");
            return 0;
        }

        log.info("Converted {} traces into documents, starting embedding...", documents.size());

        // Use a larger chunk size for traces — they're structured data, not prose
        TokenTextSplitter splitter = new TokenTextSplitter(1500, 300, 5, 10000, true);
        List<Document> chunks = splitter.split(documents);

        vectorStore.add(chunks);
        log.info("Successfully ingested {} trace documents ({} chunks) into ChromaDB",
                documents.size(), chunks.size());
        return documents.size();
    }

    /**
     * Remove any existing chunks for this traceId before re-ingesting.
     * Makes trace ingestion idempotent — same trace won't produce duplicates.
     */
    private void deduplicateTrace(String traceId) {
        try {
            vectorStore.delete("traceId == '" + traceId + "'");
        } catch (Exception e) {
            log.debug("Dedup removal for trace {} (may not exist yet): {}", traceId, e.getMessage());
        }
    }

    /**
     * Convert a single Jaeger trace JSON into a human-readable Document.
     *
     * Jaeger trace JSON structure:
     * {
     *   "traceID": "abc123",
     *   "spans": [ { spanID, operationName, serviceName, duration, tags, logs, ... } ],
     *   "processes": { "p1": { serviceName, tags } }
     * }
     */
    private Document traceToDocument(JsonNode trace) {
        String traceId = trace.path("traceID").asText("unknown");
        JsonNode spans = trace.get("spans");
        JsonNode processes = trace.get("processes");

        if (spans == null || !spans.isArray() || spans.isEmpty()) {
            return null;
        }

        // Build a process ID -> service name map
        Map<String, String> processMap = buildProcessMap(processes);

        // Parse all spans
        List<SpanInfo> spanInfos = new ArrayList<>();
        long traceStartTime = Long.MAX_VALUE;
        long traceEndTime = Long.MIN_VALUE;
        boolean hasErrors = false;

        for (JsonNode span : spans) {
            SpanInfo info = parseSpan(span, processMap);
            spanInfos.add(info);

            if (info.startTime < traceStartTime) traceStartTime = info.startTime;
            long endTime = info.startTime + info.durationMicros;
            if (endTime > traceEndTime) traceEndTime = endTime;
            if (info.hasError) hasErrors = true;
        }

        // Sort spans by start time
        spanInfos.sort((a, b) -> Long.compare(a.startTime, b.startTime));

        // Find root span (the one without a parent, or the first one)
        SpanInfo rootSpan = spanInfos.stream()
                .filter(s -> s.parentSpanId == null || s.parentSpanId.isEmpty()
                        || s.parentSpanId.equals("0000000000000000"))
                .findFirst()
                .orElse(spanInfos.get(0));

        long traceDurationMs = (traceEndTime - traceStartTime) / 1000;
        String startTimeStr = TIME_FMT.format(Instant.ofEpochMilli(traceStartTime / 1000));

        // Build human-readable narrative
        StringBuilder text = new StringBuilder();
        text.append("=== Distributed Trace ===\n");
        text.append(String.format("Trace ID: %s\n", traceId));
        text.append(String.format("Time: %s\n", startTimeStr));
        text.append(String.format("Root Operation: %s (%s)\n", rootSpan.operationName, rootSpan.serviceName));
        text.append(String.format("Total Duration: %dms\n", traceDurationMs));
        text.append(String.format("Span Count: %d\n", spanInfos.size()));
        text.append(String.format("Has Errors: %s\n", hasErrors ? "YES" : "no"));
        text.append(String.format("Services Involved: %s\n",
                spanInfos.stream().map(s -> s.serviceName).distinct().toList()));
        text.append("\n--- Span Breakdown ---\n\n");

        // Write each span as a readable entry
        for (SpanInfo span : spanInfos) {
            text.append(String.format("[%s] %s  (%dms)%s\n",
                    span.serviceName,
                    span.operationName,
                    span.durationMicros / 1000,
                    span.hasError ? "  *** ERROR ***" : ""));

            // Include span tags/attributes
            if (!span.tags.isEmpty()) {
                for (Map.Entry<String, String> tag : span.tags.entrySet()) {
                    text.append(String.format("  %s: %s\n", tag.getKey(), tag.getValue()));
                }
            }

            // Include error details if present
            if (span.hasError && span.errorMessage != null) {
                text.append(String.format("  ERROR: %s\n", span.errorMessage));
            }

            // Include log/event entries
            for (String logEntry : span.logEntries) {
                text.append(String.format("  event: %s\n", logEntry));
            }

            text.append("\n");
        }

        // Add a summary for slow traces
        if (traceDurationMs > 500) {
            text.append("--- Performance Note ---\n");
            text.append(String.format("This trace is SLOW (%dms). ", traceDurationMs));

            // Find the slowest span
            SpanInfo slowest = spanInfos.stream()
                    .max((a, b) -> Long.compare(a.durationMicros, b.durationMicros))
                    .orElse(rootSpan);
            text.append(String.format("Slowest span: [%s] %s at %dms.\n",
                    slowest.serviceName, slowest.operationName, slowest.durationMicros / 1000));
        }

        if (hasErrors) {
            text.append("--- Error Summary ---\n");
            spanInfos.stream()
                    .filter(s -> s.hasError)
                    .forEach(s -> text.append(String.format("ERROR in [%s] %s: %s\n",
                            s.serviceName, s.operationName,
                            s.errorMessage != null ? s.errorMessage : "unknown error")));
        }

        // Create metadata for filtering
        // Spring AI uses Map<String, Object> instead of LangChain4j's Metadata class
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("source", "jaeger-trace");
        metadata.put("traceId", traceId);
        metadata.put("rootService", rootSpan.serviceName);
        metadata.put("rootOperation", rootSpan.operationName);
        metadata.put("durationMs", (int) traceDurationMs);
        metadata.put("spanCount", spanInfos.size());
        metadata.put("hasErrors", hasErrors ? "true" : "false");
        metadata.put("type", "telemetry");

        return new Document(text.toString(), metadata);
    }

    private Map<String, String> buildProcessMap(JsonNode processes) {
        Map<String, String> map = new HashMap<>();
        if (processes != null) {
            processes.fields().forEachRemaining(entry -> {
                JsonNode serviceName = entry.getValue().get("serviceName");
                if (serviceName != null) {
                    map.put(entry.getKey(), serviceName.asText());
                }
            });
        }
        return map;
    }

    private SpanInfo parseSpan(JsonNode span, Map<String, String> processMap) {
        SpanInfo info = new SpanInfo();
        info.spanId = span.path("spanID").asText("");
        info.operationName = span.path("operationName").asText("unknown");
        info.startTime = span.path("startTime").asLong(0);
        info.durationMicros = span.path("duration").asLong(0);

        // Get service name from process reference
        String processId = span.path("processID").asText("");
        info.serviceName = processMap.getOrDefault(processId, "unknown");

        // Parse parent span ID from references
        JsonNode references = span.get("references");
        if (references != null && references.isArray()) {
            for (JsonNode ref : references) {
                if ("CHILD_OF".equals(ref.path("refType").asText())) {
                    info.parentSpanId = ref.path("spanID").asText("");
                    break;
                }
            }
        }

        // Parse tags
        JsonNode tags = span.get("tags");
        if (tags != null && tags.isArray()) {
            for (JsonNode tag : tags) {
                String key = tag.path("key").asText("");
                String value = tag.path("value").asText("");

                // Track error status
                if ("error".equals(key) && "true".equalsIgnoreCase(value)) {
                    info.hasError = true;
                }
                if ("otel.status_code".equals(key) && "ERROR".equals(value)) {
                    info.hasError = true;
                }

                // Store interesting tags
                if (isInterestingTag(key)) {
                    info.tags.put(key, value);
                }

                // Capture error message
                if ("error.message".equals(key) || "exception.message".equals(key)) {
                    info.errorMessage = value;
                }
            }
        }

        // Parse logs/events
        JsonNode logs = span.get("logs");
        if (logs != null && logs.isArray()) {
            for (JsonNode logEntry : logs) {
                JsonNode fields = logEntry.get("fields");
                if (fields != null && fields.isArray()) {
                    StringBuilder logText = new StringBuilder();
                    for (JsonNode field : fields) {
                        String key = field.path("key").asText("");
                        String value = field.path("value").asText("");
                        if ("message".equals(key) || "event".equals(key)) {
                            logText.append(value);
                        } else if ("exception.message".equals(key) || "error.message".equals(key)) {
                            info.hasError = true;
                            info.errorMessage = value;
                            logText.append("ERROR: ").append(value);
                        }
                    }
                    if (!logText.isEmpty()) {
                        info.logEntries.add(logText.toString());
                    }
                }
            }
        }

        return info;
    }

    /**
     * Filter for tags that are useful in the trace narrative.
     * Skip internal/noisy tags.
     */
    private boolean isInterestingTag(String key) {
        return key.startsWith("http.") ||
                key.startsWith("db.") ||
                key.startsWith("net.") ||
                key.equals("error") ||
                key.equals("error.message") ||
                key.equals("otel.status_code") ||
                key.equals("otel.status_description") ||
                key.startsWith("product.") ||
                key.startsWith("order.") ||
                key.startsWith("payment.");
    }

    /**
     * Internal record for parsed span data.
     */
    private static class SpanInfo {
        String spanId;
        String parentSpanId;
        String serviceName;
        String operationName;
        long startTime;       // microseconds since epoch
        long durationMicros;
        boolean hasError;
        String errorMessage;
        Map<String, String> tags = new HashMap<>();
        List<String> logEntries = new ArrayList<>();
    }
}
