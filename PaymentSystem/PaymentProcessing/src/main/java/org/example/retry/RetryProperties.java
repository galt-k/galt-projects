package org.example.retry;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@ConfigurationProperties("payment.retry")
public class RetryProperties {
    private Settings defaults = new Settings();
    private Map<String, Settings> policies = new LinkedHashMap<>();

    public Settings getDefaults() { return defaults; }
    public void setDefaults(Settings defaults) { this.defaults = defaults; }
    public Map<String, Settings> getPolicies() { return policies; }
    public void setPolicies(Map<String, Settings> policies) { this.policies = policies; }

    public Map<String, RetryPolicy> resolve() {
        RetryPolicy base = defaults.apply(new RetryPolicy(3, Duration.ofSeconds(1), Duration.ofSeconds(5),
                Duration.ofMillis(100), 2, Duration.ofSeconds(1), RetryPolicy.Jitter.FULL));
        var resolved = new LinkedHashMap<String, RetryPolicy>();
        // The two built-in operations also work in programmatically created Spring contexts.
        resolved.put("authorization", base);
        resolved.put("capture-ledger", base);
        policies.forEach((name, settings) -> {
            if (name == null || name.isBlank()) throw new IllegalArgumentException("Retry policy name is required");
            resolved.put(name, settings.apply(base));
        });
        return Map.copyOf(resolved);
    }

    public static class Settings {
        private Integer maxAttempts;
        private Duration attemptTimeout;
        private Duration maxElapsedTime;
        private Duration initialDelay;
        private Double multiplier;
        private Duration maxDelay;
        private RetryPolicy.Jitter jitter;

        RetryPolicy apply(RetryPolicy base) {
            return new RetryPolicy(maxAttempts == null ? base.maxAttempts() : maxAttempts,
                    attemptTimeout == null ? base.attemptTimeout() : attemptTimeout,
                    maxElapsedTime == null ? base.maxElapsedTime() : maxElapsedTime,
                    initialDelay == null ? base.initialDelay() : initialDelay,
                    multiplier == null ? base.multiplier() : multiplier,
                    maxDelay == null ? base.maxDelay() : maxDelay, jitter == null ? base.jitter() : jitter);
        }
        public Integer getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(Integer value) { maxAttempts = value; }
        public Duration getAttemptTimeout() { return attemptTimeout; }
        public void setAttemptTimeout(Duration value) { attemptTimeout = value; }
        public Duration getMaxElapsedTime() { return maxElapsedTime; }
        public void setMaxElapsedTime(Duration value) { maxElapsedTime = value; }
        public Duration getInitialDelay() { return initialDelay; }
        public void setInitialDelay(Duration value) { initialDelay = value; }
        public Double getMultiplier() { return multiplier; }
        public void setMultiplier(Double value) { multiplier = value; }
        public Duration getMaxDelay() { return maxDelay; }
        public void setMaxDelay(Duration value) { maxDelay = value; }
        public RetryPolicy.Jitter getJitter() { return jitter; }
        public void setJitter(RetryPolicy.Jitter value) { jitter = value; }
    }
}
