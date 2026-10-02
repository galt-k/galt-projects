package org.example.retry;

/** Execution stopped without proving the downstream financial operation failed. */
public final class RetryExecutionException extends RuntimeException {
    public RetryExecutionException(String message, Throwable cause) { super(message, cause); }
}
