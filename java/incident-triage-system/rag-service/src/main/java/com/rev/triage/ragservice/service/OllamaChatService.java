package com.rev.triage.ragservice.service;

import org.springframework.ai.chat.model.ChatModel;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Dedicated service for Ollama LLM chat calls, protected by Resilience4j circuit breaker.
 *
 * WHY A SEPARATE CLASS?
 *
 *   Spring AOP proxies (which Resilience4j uses) CANNOT intercept self-calls.
 *   If callLlm() lived in RagQueryService and ask() called this.callLlm(),
 *   the @CircuitBreaker annotation would be SILENTLY IGNORED — the call goes
 *   directly to the method, bypassing the proxy.
 *
 *   By extracting it into a separate @Service bean, RagQueryService gets an
 *   injected proxy of OllamaChatService. When ask() calls ollamaChatService.call(),
 *   it goes through the proxy → Resilience4j intercepts → circuit breaker works.
 *
 *   This is the #1 gotcha with Resilience4j + Spring Boot.
 *
 * CIRCUIT BREAKER BEHAVIOR:
 *
 *   CLOSED (normal):
 *     call() → chatModel.chat(prompt) → return answer
 *     Meanwhile, Resilience4j silently tracks: success/failure/slow call
 *
 *   OPEN (Ollama is down — 50%+ of last 10 calls failed):
 *     call() → CallNotPermittedException thrown IMMEDIATELY (no network call)
 *            → callFallback() returns raw context
 *     Stays open for 30 seconds, then transitions to HALF_OPEN
 *
 *   HALF_OPEN (testing recovery):
 *     Allows 3 calls through to Ollama
 *     If they succeed → back to CLOSED
 *     If they fail → back to OPEN for another 30s
 */
@Service
public class OllamaChatService {

    private static final Logger log = LoggerFactory.getLogger(OllamaChatService.class);

    private final ChatModel chatModel;

    public OllamaChatService(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /**
     * Send a prompt to Ollama LLM, protected by circuit breaker.
     *
     * @param prompt  the full RAG prompt (system instructions + context + question)
     * @param context the annotated retrieved chunks (passed through to fallback)
     * @return LLM-generated answer, or fallback response if Ollama is down
     *
     * ANNOTATION BREAKDOWN:
     *   @CircuitBreaker(
     *     name = "ollamaChat"       → links to resilience4j.circuitbreaker.instances.ollamaChat in YAML
     *     fallbackMethod = "..."     → method called when exception or breaker OPEN
     *   )
     *
     * WHY 'context' IS A PARAMETER:
     *   We don't use 'context' in this method — we use 'prompt' (which contains the context).
     *   But the fallback method needs the context to return raw chunks.
     *   Resilience4j requires the fallback to have the SAME parameters + Throwable.
     *   So we pass 'context' through as a parameter specifically for the fallback.
     */
    @CircuitBreaker(name = "ollamaChat", fallbackMethod = "callFallback")
    public String call(String prompt, String context) {
        log.info("Calling Ollama LLM (circuit breaker: ollamaChat)...");
        String answer = chatModel.call(prompt);
        log.info("Ollama LLM responded successfully");
        return answer;
    }

    /**
     * Fallback when Ollama call fails or circuit breaker is OPEN.
     *
     * FALLBACK RULES:
     *   1. Same parameters as the protected method + one Throwable at the end
     *   2. Same return type (String)
     *   3. Must be in the SAME class
     *   4. Can be private — but we keep it package-private for testability
     *
     * WHEN THIS IS CALLED:
     *   - chatModel.chat() throws ANY recorded exception (RuntimeException, IOException, etc.)
     *   - Circuit breaker is OPEN → CallNotPermittedException (no actual call made)
     *
     * GRACEFUL DEGRADATION STRATEGY:
     *   Level 0: Everything works → full LLM answer
     *   Level 1: Ollama down → return raw retrieved chunks (THIS fallback)
     *
     *   The user still gets the relevant traces/docs they were looking for.
     *   They just don't get the LLM's summary/analysis on top.
     *   This is significantly more useful than "Service unavailable, try again later."
     */
    String callFallback(String prompt, String context, Throwable t) {
        // FOR THE ENGINEER: full technical detail in logs.
        // Differentiate circuit-open (expected, high volume) from actual failures (needs investigation).
        if (t instanceof CallNotPermittedException) {
            // Circuit is OPEN. Ollama is known to be down. No point logging the stack trace.
            // This will fire for EVERY request while the circuit is open (30s).
            log.warn("Circuit breaker OPEN for ollamaChat — rejecting call instantly");
        } else {
            // Actual failure from Ollama. Log the cause so the on-call engineer can debug.
            log.error("Ollama LLM call failed [{}]: {}",
                    t.getClass().getSimpleName(), t.getMessage());
        }

        // FOR THE USER: no exception classes, no internal service names, no "circuit breaker" jargon.
        // The user sees: "I couldn't summarize, but here's what I found."
        // The raw context chunks are still useful — the user can read the traces/docs directly.
        return String.format("""
                The AI assistant is temporarily unavailable, but I found relevant context from the knowledge base:

                %s

                Please try again shortly for a summarized answer.""",
                context);
    }
}
