package dev.concurrencylab.admission;

/** A separate handle belongs to each accepted submission, not each payload object. */
public sealed interface AdmissionResult {
    record Accepted(CancellationHandle cancellation) implements AdmissionResult {}
    record Rejected(Reason reason) implements AdmissionResult {}
    enum Reason { FULL, SHUTDOWN, DUPLICATE }
}
