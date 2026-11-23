package dev.concurrencylab.admission;

/**
 * Request envelope. Callers must not mutate the payload while the request is pending.
 * Validation belongs to submit so invalid-input behavior can be exercised there.
 */
public record InferenceRequest(String id, Object payload) {}
