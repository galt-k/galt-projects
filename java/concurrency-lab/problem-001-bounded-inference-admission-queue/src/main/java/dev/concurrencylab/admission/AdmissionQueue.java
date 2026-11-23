package dev.concurrencylab.admission;

import java.util.Optional;
import java.time.Duration;

/** Contract only. The learner supplies the implementation and synchronization design. */
public interface AdmissionQueue<T> {
    int CAPACITY = 1024;
    int PRODUCER_COUNT = 16;

    /** Attempts admission without waiting for capacity. */
    AdmissionResult submit(T request);

    /** Single scheduler only. Returning a request constitutes dispatch. */
    Optional<T> poll();

    /** Closes admission; the scheduler must continue draining accepted work. */
    void shutdown();

    /** True once closed and all accepted work has a terminal disposition. */
    boolean isTerminated();
    /**
     * Waits for termination without initiating shutdown. Each call has its own timeout.
     * Returns true on termination, false on timeout. Zero performs an immediate check.
     * Null/negative timeouts are invalid. Interruption ends only this caller's wait.
     */
    boolean awaitTermination(Duration timeout) throws InterruptedException;
}

