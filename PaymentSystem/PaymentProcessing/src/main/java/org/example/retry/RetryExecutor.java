package org.example.retry;

public interface RetryExecutor {
    Object execute(String policyName, RetryPolicy policy, RetryClassifier classifier, Attempt operation) throws Throwable;

    @FunctionalInterface
    interface Attempt { Object call() throws Throwable; }
}
