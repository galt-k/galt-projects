package org.example.retry;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

public final class RetryAfter {
    private RetryAfter() {}

    public static Duration parse(String value, Instant now) {
        if (value == null || value.isBlank()) return Duration.ZERO;
        try {
            if (value.matches("[0-9]+")) return Duration.ofSeconds(Long.parseLong(value));
            Duration delay = Duration.between(now, ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
            return delay.isNegative() ? Duration.ZERO : delay;
        } catch (RuntimeException invalid) {
            // Unparseable guidance must not cause an immediate retry that could violate the provider's wait.
            return Duration.ofDays(1);
        }
    }
}
