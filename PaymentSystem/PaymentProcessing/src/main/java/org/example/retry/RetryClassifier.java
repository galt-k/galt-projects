package org.example.retry;

@FunctionalInterface
public interface RetryClassifier {
    RetryDecision classify(Object result, Throwable failure);
}
