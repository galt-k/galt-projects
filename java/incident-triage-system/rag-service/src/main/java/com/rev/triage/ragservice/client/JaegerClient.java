package com.rev.triage.ragservice.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

/**
 * HTTP client for Jaeger's REST API.
 * Pulls traces for a given service within a lookback window.
 *
 * Jaeger API docs: https://www.jaegertracing.io/docs/apis/#http-json
 * Key endpoints:
 *   GET /api/services           → list of traced services
 *   GET /api/traces?service=X   → traces for a service
 *
 * RESILIENCE STRATEGY: @Retry (not circuit breaker)
 *
 *   WHY RETRY (not circuit breaker) FOR JAEGER:
 *     1. These calls are FAST (<100ms each). Retrying 3 times = 300ms worst case.
 *        Unlike Ollama (10-30s per call), retrying is cheap here.
 *     2. These calls run in the BACKGROUND (startup + every 5 min scheduler).
 *        No user is waiting. A short retry delay is invisible.
 *     3. Transient failures are the most common failure mode for Jaeger:
 *        - Pod just restarted (K8s rolling update)
 *        - Brief network hiccup between pods
 *        - Jaeger is temporarily overloaded
 *        These self-resolve in 1-2 seconds. A retry handles them perfectly.
 *     4. Failure consequence is DATA LOSS — if we don't fetch traces,
 *        the RAG knowledge base has gaps. Worth retrying.
 *     5. These calls are READ-ONLY (idempotent). Retrying is always safe.
 *
 *   WHY NOT circuit breaker:
 *     - Circuit breaker protects USER-FACING calls from slow dependencies.
 *     - Jaeger calls are background work. If Jaeger is down for 5 minutes,
 *       we just miss one polling cycle. The next cycle will catch up.
 *     - No thread pool exhaustion risk (it's a single scheduled thread).
 *
 *   SELF-CALL PROBLEM:
 *     getAllTraces() calls this.getServices() and this.getTraces() — self-calls.
 *     Spring AOP won't intercept self-calls, so @Retry on getServices()/getTraces()
 *     would be SILENTLY IGNORED when called from getAllTraces().
 *
 *     SOLUTION: @Retry on getAllTraces() — the method called from OUTSIDE (by
 *     TelemetryIngestionService). This retries the entire batch if Jaeger is down.
 *
 *     But we ALSO want per-service retry — if fetching order-service traces fails,
 *     we shouldn't retry ALL services. So we use MANUAL retry inside getTraces()
 *     for the per-call level, and @Retry on getAllTraces() as the outer safety net.
 *
 *   RETRY CONFIG (in application.yml):
 *     maxAttempts: 3          → try up to 3 times total (1 original + 2 retries)
 *     waitDuration: 2s        → wait 2 seconds between retries (give Jaeger time to recover)
 *     retryExceptions:        → only retry on network/IO exceptions (not parsing errors)
 */
@Component
public class JaegerClient {

    private static final Logger log = LoggerFactory.getLogger(JaegerClient.class);

    private static final int MAX_PER_CALL_RETRIES = 2;
    private static final long RETRY_DELAY_MS = 1000;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public JaegerClient(@Value("${rag.jaeger.api-url}") String jaegerApiUrl) {
        this.restClient = RestClient.builder()
                .baseUrl(jaegerApiUrl)
                .build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Fetch available service names from Jaeger.
     *
     * Uses manual retry (not @Retry) because this is called from getAllTraces()
     * within the same class — Spring AOP self-call problem.
     */
    public List<String> getServices() {
        // Manual retry loop — we can't use @Retry here due to self-call from getAllTraces().
        for (int attempt = 1; attempt <= MAX_PER_CALL_RETRIES + 1; attempt++) {
            try {
                String response = restClient.get()
                        .uri("/api/services")
                        .retrieve()
                        .body(String.class);

                JsonNode root = objectMapper.readTree(response);
                JsonNode data = root.get("data");
                List<String> services = new ArrayList<>();
                if (data != null && data.isArray()) {
                    for (JsonNode service : data) {
                        services.add(service.asText());
                    }
                }
                log.info("Found {} services in Jaeger", services.size());
                return services;
            } catch (Exception e) {
                if (attempt <= MAX_PER_CALL_RETRIES) {
                    // FOR THE ENGINEER: log the retry attempt with full detail
                    log.warn("Jaeger /api/services failed (attempt {}/{}): [{}] {}. Retrying in {}ms...",
                            attempt, MAX_PER_CALL_RETRIES + 1,
                            e.getClass().getSimpleName(), e.getMessage(), RETRY_DELAY_MS);
                    try {
                        Thread.sleep(RETRY_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                } else {
                    // All retries exhausted
                    log.error("Jaeger /api/services failed after {} attempts: [{}] {}",
                            attempt, e.getClass().getSimpleName(), e.getMessage());
                }
            }
        }
        return List.of();
    }

    /**
     * Fetch traces for a specific service.
     *
     * Uses manual retry (not @Retry) because this is called from getAllTraces()
     * within the same class — Spring AOP self-call problem.
     *
     * @param service   the service name (e.g., "order-service")
     * @param lookback  lookback duration (e.g., "1h", "30m", "2d")
     * @param limit     max number of traces to fetch
     * @return raw JSON traces as a JsonNode array
     */
    public List<JsonNode> getTraces(String service, String lookback, int limit) {
        // Manual retry loop — same reason as getServices()
        for (int attempt = 1; attempt <= MAX_PER_CALL_RETRIES + 1; attempt++) {
            try {
                String response = restClient.get()
                        .uri(uriBuilder -> uriBuilder
                                .path("/api/traces")
                                .queryParam("service", service)
                                .queryParam("lookback", lookback)
                                .queryParam("limit", limit)
                                .build())
                        .retrieve()
                        .body(String.class);

                JsonNode root = objectMapper.readTree(response);
                JsonNode data = root.get("data");
                List<JsonNode> traces = new ArrayList<>();
                if (data != null && data.isArray()) {
                    for (JsonNode trace : data) {
                        traces.add(trace);
                    }
                }
                log.info("Fetched {} traces for service '{}' (lookback: {})", traces.size(), service, lookback);
                return traces;
            } catch (Exception e) {
                if (attempt <= MAX_PER_CALL_RETRIES) {
                    log.warn("Jaeger /api/traces for '{}' failed (attempt {}/{}): [{}] {}. Retrying in {}ms...",
                            service, attempt, MAX_PER_CALL_RETRIES + 1,
                            e.getClass().getSimpleName(), e.getMessage(), RETRY_DELAY_MS);
                    try {
                        Thread.sleep(RETRY_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                } else {
                    log.error("Jaeger /api/traces for '{}' failed after {} attempts: [{}] {}",
                            service, attempt, e.getClass().getSimpleName(), e.getMessage());
                }
            }
        }
        return List.of();
    }

    /**
     * Fetch all traces across all known services.
     *
     * @Retry on this method works because TelemetryIngestionService calls it
     * through Spring's AOP proxy (different bean → proxy intercepts → @Retry works).
     *
     * This is the OUTER safety net. If Jaeger is completely unreachable even after
     * the per-call manual retries, the @Retry here will retry the entire batch.
     * This handles the case where Jaeger comes back mid-batch.
     */
    @Retry(name = "jaegerApi", fallbackMethod = "getAllTracesFallback")
    public List<JsonNode> getAllTraces(String lookback, int limit) {
        List<String> services = getServices();

        // If getServices() returned empty after retries, Jaeger is likely down.
        // Throw an exception so @Retry can catch it and retry the whole method.
        if (services.isEmpty()) {
            throw new JaegerUnavailableException("Jaeger returned no services — API may be down");
        }

        List<JsonNode> allTraces = new ArrayList<>();

        for (String service : services) {
            // Skip jaeger's own internal service
            if (service.equals("jaeger-query") || service.equals("jaeger-all-in-one")) {
                continue;
            }
            List<JsonNode> traces = getTraces(service, lookback, limit);
            allTraces.addAll(traces);
        }

        log.info("Fetched {} total traces across {} services", allTraces.size(), services.size());
        return allTraces;
    }

    /**
     * Fallback when ALL retry attempts for getAllTraces() are exhausted.
     * Jaeger is truly down — log it and return empty. The next scheduled poll will try again.
     */
    List<JsonNode> getAllTracesFallback(String lookback, int limit, Throwable t) {
        log.error("Jaeger API unavailable after all retries: [{}] {}. " +
                  "Trace ingestion skipped — will retry on next scheduled poll.",
                t.getClass().getSimpleName(), t.getMessage());
        return List.of();
    }

    /**
     * Custom exception to signal that Jaeger is unreachable.
     * Used to trigger @Retry on getAllTraces() when getServices() returns empty.
     */
    static class JaegerUnavailableException extends RuntimeException {
        JaegerUnavailableException(String message) {
            super(message);
        }
    }
}
