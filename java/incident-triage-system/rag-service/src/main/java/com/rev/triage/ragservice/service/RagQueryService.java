package com.rev.triage.ragservice.service;

import com.rev.triage.ragservice.config.RagConfig;
import com.rev.triage.ragservice.rag.QueryClassifier;
import com.rev.triage.ragservice.rag.QueryClassifier.ClassifiedQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * RAG query service — orchestrates retrieval + LLM generation.
 *
 * Migrated from LangChain4j → Spring AI:
 *   - ContentRetriever.retrieve(Query)  → VectorStore.similaritySearch(SearchRequest)
 *   - List<Content>                     → List<Document>
 *   - Content.textSegment().text()      → Document.getText()
 *   - Content.textSegment().metadata()  → Document.getMetadata()
 *
 * RESILIENCE ARCHITECTURE:
 *
 *   ask() makes TWO external calls, both depend on Ollama:
 *     1. vectorStore.similaritySearch() → Ollama /api/embed (embed the query) + ChromaDB (vector search)
 *     2. ollamaChatService.call()       → Ollama /api/chat  (generate answer)  ← CIRCUIT BREAKER PROTECTED
 *
 *   FAILURE MODES:
 *     - Ollama down → BOTH calls fail. Step 1 fails first (can't embed query).
 *       We catch this and return a graceful "service unavailable" message.
 *     - ChromaDB down, Ollama up → Step 1 fails (can't search vectors). Same handling.
 *     - Ollama up, but slow → Step 2's circuit breaker detects slowness and trips.
 *       Fallback returns raw retrieved chunks (useful > nothing).
 *
 *   WHY THE EMBEDDING FAILURE ISN'T CIRCUIT-BREAKER-PROTECTED:
 *     The embedding is done inside Spring AI's VectorStore — we don't directly call it.
 *     We wrap it in a try-catch instead. If Ollama recovers, the next request will work.
 *     A circuit breaker here would add complexity with little benefit (retrieval is not user-facing latency).
 *
 *   The LLM call IS delegated to OllamaChatService (separate bean) because:
 *     - Spring AOP proxies don't intercept self-calls (this.method())
 *     - @CircuitBreaker on a method called from the SAME class is SILENTLY IGNORED
 *     - By injecting OllamaChatService, the call goes through Spring's proxy → Resilience4j works
 */
@Service
public class RagQueryService {

    private static final Logger log = LoggerFactory.getLogger(RagQueryService.class);

    private final VectorStore vectorStore;
    private final RagConfig ragConfig;
    private final OllamaChatService ollamaChatService;

    public RagQueryService(VectorStore vectorStore,
                           RagConfig ragConfig,
                           OllamaChatService ollamaChatService) {
        this.vectorStore = vectorStore;
        this.ragConfig = ragConfig;
        this.ollamaChatService = ollamaChatService;
    }

    public String ask(String question) {
        log.info("Received question: {}", question);

        // Step 1: Classify the query
        ClassifiedQuery cq = QueryClassifier.classify(question);
        log.info("Query classified as {} (service: {})", cq.intent(), cq.serviceName());

        // Step 2: Retrieve relevant chunks from ChromaDB
        // This involves TWO calls: Ollama /api/embed (to vectorize the question) + ChromaDB (to search).
        // If Ollama is down, the embedding call fails BEFORE we even get to the LLM chat.
        // We catch this and return a graceful error — no 500 for the user.
        List<Document> relevantDocs;
        try {
            SearchRequest searchRequest = ragConfig.buildSearchRequest(question);
            relevantDocs = vectorStore.similaritySearch(searchRequest);
            log.info("Retrieved {} relevant chunks from vector store", relevantDocs.size());
        } catch (Exception e) {
            // FOR THE ENGINEER: full technical detail — exception class, message, internal service names.
            // This is what you grep in Kibana/Loki at 3 AM when the service is down.
            log.error("Failed to retrieve context (embedding/ChromaDB may be down): [{}] {}",
                    e.getClass().getSimpleName(), e.getMessage());

            // FOR THE USER: clean message — no internal service names, no exception classes, no URLs.
            // The user doesn't know what "ResourceAccessException" or "ChromaDB" is.
            // Also: leaking internal URLs (http://ollama:11434) in API responses is a security risk.
            return "The knowledge base is temporarily unavailable. " +
                   "Please try again in a few moments.";
        }

        // Log chunk sources for debugging
        relevantDocs.forEach(doc -> {
            String type = (String) doc.getMetadata().get("type");
            String id = "telemetry".equals(type)
                    ? (String) doc.getMetadata().get("traceId")
                    : (String) doc.getMetadata().get("source");
            log.debug("  Chunk: type={}, id={}", type, id);
        });

        if (relevantDocs.isEmpty()) {
            return "I don't have enough context to answer that question. " +
                   "Try ingesting documents first via POST /ingest or wait for auto-ingestion.";
        }

        // Step 3: Build context with metadata annotations
        String context = buildAnnotatedContext(relevantDocs);

        // Step 4: Build prompt
        String prompt = buildPrompt(cq, context, question);

        // Step 5: Call LLM via OllamaChatService (circuit breaker protected)
        // This call goes through Spring's AOP proxy → Resilience4j intercepts it.
        // If Ollama is down → breaker trips → fallback returns raw context to user.
        return ollamaChatService.call(prompt, context);
    }

    // --- Helper methods (pure logic, no external calls, no resilience needed) ---

    private String buildAnnotatedContext(List<Document> relevantDocs) {
        return relevantDocs.stream()
                .map(doc -> {
                    String type = (String) doc.getMetadata().get("type");
                    String prefix = "";
                    if ("telemetry".equals(type)) {
                        String traceId = (String) doc.getMetadata().get("traceId");
                        String svc = (String) doc.getMetadata().get("rootService");
                        prefix = String.format("[TRACE | service=%s | traceId=%s]\n", svc, traceId);
                    } else if ("documentation".equals(type)) {
                        String source = (String) doc.getMetadata().get("source");
                        prefix = String.format("[DOC | source=%s]\n", source);
                    }
                    return prefix + doc.getText();
                })
                .collect(Collectors.joining("\n\n---\n\n"));
    }

    private String buildPrompt(ClassifiedQuery cq, String context, String question) {
        return String.format("""
                You are an incident triage assistant for a distributed microservices system \
                (product-service, order-service, payment-service, rag-service).

                QUERY TYPE: %s
                %s

                INSTRUCTIONS:
                - Answer ONLY based on the provided context below.
                - When referencing traces, ALWAYS cite the trace ID (e.g., "Trace abc123 shows...").
                - When referencing documentation, cite the source file name.
                - For error analysis: identify root cause, affected services, and error propagation path.
                - For performance analysis: identify the slowest spans and bottleneck services with durations.
                - If the context is insufficient, say so explicitly rather than speculating.
                - Be precise and concise. Use bullet points for multi-part answers.

                CONTEXT:
                %s

                QUESTION: %s

                ANSWER:""",
                cq.intent(),
                cq.serviceName() != null ? "SERVICE FOCUS: " + cq.serviceName() : "",
                context,
                question);
    }
}
