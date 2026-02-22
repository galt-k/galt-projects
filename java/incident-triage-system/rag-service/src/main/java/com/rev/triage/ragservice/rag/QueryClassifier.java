package com.rev.triage.ragservice.rag;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Classifies incoming RAG questions by intent using keyword matching.
 * No LLM call — purely rule-based for speed and determinism.
 *
 * Priority order: ERROR > PERFORMANCE > SERVICE_SPECIFIC > ARCHITECTURE > GENERAL
 */
public class QueryClassifier {

    public enum QueryIntent {
        ERROR_ANALYSIS,
        PERFORMANCE_ANALYSIS,
        SERVICE_SPECIFIC,
        ARCHITECTURE_DOCS,
        GENERAL
    }

    public record ClassifiedQuery(QueryIntent intent, String serviceName) {}

    private static final List<String> ERROR_KEYWORDS = List.of(
            "error", "fail", "failed", "exception", "500", "4xx", "5xx",
            "crash", "broken", "bug", "issue", "wrong", "problem", "fault"
    );

    private static final List<String> PERF_KEYWORDS = List.of(
            "slow", "latency", "duration", "timeout", "performance",
            "p99", "p95", "bottleneck", "fast", "speed", "response time"
    );

    private static final List<String> ARCH_KEYWORDS = List.of(
            "architecture", "design", "how does", "how do", "what is",
            "explain", "documentation", "pattern", "strategy", "structure",
            "why did we", "why do we", "tracing work", "decision"
    );

    private static final Pattern SERVICE_PATTERN = Pattern.compile(
            "(product-service|order-service|payment-service|rag-service)",
            Pattern.CASE_INSENSITIVE
    );

    public static ClassifiedQuery classify(String question) {
        String lower = question.toLowerCase(Locale.ROOT);
        String serviceName = extractServiceName(lower);

        if (containsAny(lower, ERROR_KEYWORDS)) {
            return new ClassifiedQuery(QueryIntent.ERROR_ANALYSIS, serviceName);
        }
        if (containsAny(lower, PERF_KEYWORDS)) {
            return new ClassifiedQuery(QueryIntent.PERFORMANCE_ANALYSIS, serviceName);
        }
        if (serviceName != null && !containsAny(lower, ARCH_KEYWORDS)) {
            return new ClassifiedQuery(QueryIntent.SERVICE_SPECIFIC, serviceName);
        }
        if (containsAny(lower, ARCH_KEYWORDS)) {
            return new ClassifiedQuery(QueryIntent.ARCHITECTURE_DOCS, serviceName);
        }
        return new ClassifiedQuery(QueryIntent.GENERAL, serviceName);
    }

    private static String extractServiceName(String lower) {
        Matcher m = SERVICE_PATTERN.matcher(lower);
        return m.find() ? m.group(1).toLowerCase(Locale.ROOT) : null;
    }

    private static boolean containsAny(String text, List<String> keywords) {
        return keywords.stream().anyMatch(text::contains);
    }
}
