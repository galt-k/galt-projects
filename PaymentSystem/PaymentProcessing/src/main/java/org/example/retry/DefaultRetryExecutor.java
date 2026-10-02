package org.example.retry;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.LongAdder;

public final class DefaultRetryExecutor implements RetryExecutor, AutoCloseable {
    private static final System.Logger LOGGER = System.getLogger(DefaultRetryExecutor.class.getName());
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final LongAdder attempts = new LongAdder();
    private final LongAdder retries = new LongAdder();

    public long attempts() { return attempts.sum(); }
    public long retries() { return retries.sum(); }

    @Override
    public Object execute(String name, RetryPolicy policy, RetryClassifier classifier, Attempt operation) throws Throwable {
        long started = System.nanoTime();
        Object result = null;
        Throwable failure = null;
        for (int attempt = 1; attempt <= policy.maxAttempts(); attempt++) {
            if (Thread.currentThread().isInterrupted()) throw new RetryExecutionException("Retry execution interrupted", null);
            long remaining = policy.maxElapsedTime().toNanos() - (System.nanoTime() - started);
            if (remaining <= 0) return finish(result, failure);
            Duration timeout = Duration.ofNanos(Math.min(remaining, policy.attemptTimeout().toNanos()));
            attempts.increment();
            if (attempt > 1) retries.increment();
            var future = workers.submit(() -> {
                RetryAttempt.start(timeout);
                try {
                    return new AttemptResult(operation.call(), null);
                } catch (Throwable error) {
                    return new AttemptResult(null, error);
                } finally {
                    RetryAttempt.clear();
                }
            });
            try {
                var observed = future.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
                result = observed.result();
                failure = observed.failure();
            } catch (TimeoutException exception) {
                future.cancel(true);
                // Cancellation cannot prove a remote side effect stopped. Do not overlap another attempt.
                throw new RetryExecutionException("Attempt deadline exceeded; outcome is uncertain", exception);
            } catch (InterruptedException exception) {
                future.cancel(true);
                Thread.currentThread().interrupt();
                throw new RetryExecutionException("Retry execution interrupted; outcome is uncertain", exception);
            } catch (ExecutionException exception) {
                throw new RetryExecutionException("Unable to execute downstream attempt", exception.getCause());
            }
            if (failure instanceof Error) throw failure;
            RetryDecision decision = classifier.classify(result, failure);
            if (decision.action() != RetryDecision.Action.RETRY || attempt == policy.maxAttempts()) return finish(result, failure);
            Duration delay = policy.delayAfter(attempt, ThreadLocalRandom.current().nextDouble());
            if (decision.minimumDelay().compareTo(delay) > 0) delay = decision.minimumDelay();
            remaining = policy.maxElapsedTime().toNanos() - (System.nanoTime() - started);
            if (remaining <= 0 || delay.compareTo(Duration.ofNanos(remaining)) >= 0) return finish(result, failure);
            LOGGER.log(System.Logger.Level.DEBUG, "Retry policy {0}: scheduling attempt {1} after {2}", name, attempt + 1, delay);
            try {
                TimeUnit.NANOSECONDS.sleep(delay.toNanos());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new RetryExecutionException("Retry backoff interrupted; outcome is uncertain", exception);
            }
        }
        return finish(result, failure);
    }

    private static Object finish(Object result, Throwable failure) throws Throwable {
        if (failure != null) throw failure;
        if (result == null) throw new RetryExecutionException("No downstream outcome available", null);
        return result;
    }

    @Override
    public void close() {
        workers.shutdownNow();
    }

    private record AttemptResult(Object result, Throwable failure) {}
}
