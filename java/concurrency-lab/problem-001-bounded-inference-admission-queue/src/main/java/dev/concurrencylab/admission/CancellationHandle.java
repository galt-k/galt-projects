package dev.concurrencylab.admission;

public interface CancellationHandle {
    /**
     * True only if this call wins cancellation before dispatch.
     * False if already cancelled or dispatched. A successful cancellation prevents delivery.
     */
    boolean cancel();
}
