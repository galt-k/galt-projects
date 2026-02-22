package com.rev.triage.ragservice.config;

import com.rev.triage.ragservice.rag.QueryClassifier;
import com.rev.triage.ragservice.rag.QueryClassifier.ClassifiedQuery;
import com.rev.triage.ragservice.rag.QueryClassifier.QueryIntent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.stereotype.Component;

/**
 * Dynamic retrieval configuration for the RAG pipeline.
 *
 * Migrated from LangChain4j ContentRetriever → Spring AI VectorStore + SearchRequest.
 *
 * WHAT CHANGED:
 *   LangChain4j used a ContentRetriever bean with dynamicMaxResults/dynamicMinScore/dynamicFilter
 *   lambdas that were evaluated per-query. Spring AI doesn't have that abstraction — instead,
 *   we build a SearchRequest per-query with the same dynamic logic.
 *
 * WHAT STAYED THE SAME:
 *   - QueryClassifier determines intent (ERROR_ANALYSIS, PERFORMANCE, etc.)
 *   - Different intents get different maxResults, minScore, and metadata filters
 *   - Service name extraction narrows telemetry queries to a specific service
 *
 * SPRING AI FILTER SYNTAX:
 *   LangChain4j: metadataKey("type").isEqualTo("telemetry").and(metadataKey("hasErrors").isEqualTo("true"))
 *   Spring AI:   "type == 'telemetry' && hasErrors == 'true'"
 *   Much simpler — just a string DSL.
 */
@Component
public class RagConfig {

    private static final Logger log = LoggerFactory.getLogger(RagConfig.class);

    /**
     * Build a SearchRequest tailored to the classified query intent.
     *
     * @param question the user's question
     * @return a SearchRequest with appropriate topK, similarityThreshold, and filterExpression
     */
    public SearchRequest buildSearchRequest(String question) {
        ClassifiedQuery cq = QueryClassifier.classify(question);
        log.debug("Query classified as {} (service: {})", cq.intent(), cq.serviceName());

        int topK = resolveMaxResults(cq);
        double minScore = resolveMinScore(cq);
        String filter = resolveFilter(cq);

        SearchRequest.Builder builder = SearchRequest.builder()
                .query(question)
                .topK(topK)
                .similarityThreshold(minScore);

        if (filter != null) {
            builder.filterExpression(filter);
        }

        return builder.build();
    }

    // --- Dynamic retrieval logic based on query classification ---

    private int resolveMaxResults(ClassifiedQuery cq) {
        return switch (cq.intent()) {
            case ERROR_ANALYSIS -> 7;
            case PERFORMANCE_ANALYSIS -> 7;
            case ARCHITECTURE_DOCS -> 5;
            case SERVICE_SPECIFIC -> 6;
            case GENERAL -> 5;
        };
    }

    private double resolveMinScore(ClassifiedQuery cq) {
        return switch (cq.intent()) {
            case ERROR_ANALYSIS -> 0.4;
            case PERFORMANCE_ANALYSIS -> 0.45;
            case ARCHITECTURE_DOCS -> 0.5;
            case SERVICE_SPECIFIC -> 0.45;
            case GENERAL -> 0.5;
        };
    }

    /**
     * Build a Spring AI filter expression string based on query intent.
     *
     * Spring AI filter DSL examples:
     *   "type == 'telemetry'"
     *   "type == 'telemetry' && hasErrors == 'true'"
     *   "type == 'telemetry' && rootService == 'payment-service'"
     */
    private String resolveFilter(ClassifiedQuery cq) {
        String filter = switch (cq.intent()) {
            case ERROR_ANALYSIS ->
                    "type == 'telemetry' && hasErrors == 'true'";
            case PERFORMANCE_ANALYSIS ->
                    "type == 'telemetry'";
            case ARCHITECTURE_DOCS ->
                    "type == 'documentation'";
            case SERVICE_SPECIFIC ->
                    "type == 'telemetry'";
            case GENERAL ->
                    null; // no filter — search everything
        };

        // If a service name was extracted and intent is telemetry-oriented, narrow by service
        if (cq.serviceName() != null && filter != null
                && cq.intent() != QueryIntent.ARCHITECTURE_DOCS) {
            filter = filter + " && rootService == '" + cq.serviceName() + "'";
        }

        return filter;
    }
}
