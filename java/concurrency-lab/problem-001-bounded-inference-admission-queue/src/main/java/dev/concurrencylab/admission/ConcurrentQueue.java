package dev.concurrencylab.admission;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Review exercise: submission identity reuse defect corrected.
 * Termination waiting uses independent caller timeouts; see termination-review.md.
 * Not a production-ready implementation. Review against review-exercise.md.
 */
public class ConcurrentQueue implements AdmissionQueue<InferenceRequest> {
    private enum Lifecycle { OPEN, DRAINING, TERMINATED }
    private enum RequestState { ACCEPTED, CANCELLED, DISPATCHED }

    private static final class Node {
        final long submissionId;
        final InferenceRequest request;
        RequestState state = RequestState.ACCEPTED;
        Node previous;
        Node next;

        Node(long submissionId, InferenceRequest request) {
            this.submissionId = submissionId;
            this.request = request;
        }
    }

    private final Object lock = new Object();
    private final CompletableFuture<Boolean> termination = new CompletableFuture<>();
    private final Map<String, Node> byRequestId = new HashMap<>();
    private final Map<Long, Node> bySubmissionId = new HashMap<>();
    private Node head;
    private Node tail;
    private int occupancy;
    private long nextSubmissionId;
    private Lifecycle lifecycle = Lifecycle.OPEN;

    @Override
    public AdmissionResult submit(InferenceRequest request) {
        Objects.requireNonNull(request, "A valid request is required");
        String id = request.id();
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("A valid request ID is required");
        }

        synchronized (lock) {
            if (byRequestId.containsKey(id)) {
                return new AdmissionResult.Rejected(AdmissionResult.Reason.DUPLICATE);
            }
            if (lifecycle != Lifecycle.OPEN) {
                return new AdmissionResult.Rejected(AdmissionResult.Reason.SHUTDOWN);
            }
            if (occupancy >= CAPACITY) {
                return new AdmissionResult.Rejected(AdmissionResult.Reason.FULL);
            }
            if (nextSubmissionId == Long.MAX_VALUE) {
                throw new IllegalStateException("Submission identity space exhausted");
            }

            long submissionId = nextSubmissionId++;
            Node node = new Node(submissionId, request);
            AdmissionResult accepted = new AdmissionResult.Accepted(
                    () -> cancelSubmission(submissionId));
            byRequestId.put(id, node);
            bySubmissionId.put(submissionId, node);
            append(node);
            occupancy++;
            return accepted;
        }
    }

    private boolean cancelSubmission(long submissionId) {
        synchronized (lock) {
            Node node = bySubmissionId.get(submissionId);
            if (node == null || node.state != RequestState.ACCEPTED) {
                return false;
            }
            retire(node, RequestState.CANCELLED);
            return true;
        }
    }

    @Override
    public Optional<InferenceRequest> poll() {
        synchronized (lock) {
            if (head == null) {
                return Optional.empty();
            }
            Node node = head;
            retire(node, RequestState.DISPATCHED);//basically to remove the node from the queue?
            return Optional.of(node.request);
        }
    }

    @Override
    public void shutdown() {
        synchronized (lock) {
            if (lifecycle == Lifecycle.OPEN) {
                lifecycle = occupancy == 0 ? Lifecycle.TERMINATED : Lifecycle.DRAINING;
                if (lifecycle == Lifecycle.TERMINATED) {
                    termination.complete(true);
                }
            }
        }
    }

    @Override
    public boolean isTerminated() {
        synchronized (lock) {
            return lifecycle == Lifecycle.TERMINATED;
        }
    }

    @Override
    public boolean awaitTermination(Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout, "A timeout is required");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("Timeout must not be negative");
        }
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        if (timeout.isZero()) {
            return isTerminated();
        }
        long nanos;
        try {
            nanos = timeout.toNanos();
        } catch (ArithmeticException overflow) {
            nanos = Long.MAX_VALUE;
        }
        try {
            return termination.get(nanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException timeoutExpired) {
            return false;
        } catch (ExecutionException failure) {
            throw new IllegalStateException("Termination signal failed", failure.getCause());
        }
    }

    /** Caller holds lock. */
    private void append(Node node) {
        node.previous = tail;
        if (tail == null) {
            head = node;
        } else {
            tail.next = node;
        }
        tail = node;
    }

    /** Caller holds lock and node is an active submission. */
    private void retire(Node node, RequestState terminalState) {
        node.state = terminalState;
        if (node.previous == null) {
            head = node.next;
        } else {
            node.previous.next = node.next;
        }
        if (node.next == null) {
            tail = node.previous;
        } else {
            node.next.previous = node.previous;
        }
        node.previous = null;
        node.next = null;
        byRequestId.remove(node.request.id(), node);
        bySubmissionId.remove(node.submissionId, node);
        occupancy--;
        if (occupancy == 0) {
            if (lifecycle == Lifecycle.DRAINING) {
                lifecycle = Lifecycle.TERMINATED;
                termination.complete(true);
            }
        }
    }
}