package org.killbill.billing.payment.checkout;

import org.example.CallContext;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public record CheckoutCallContext(UUID requestId, String idempotencyKey) implements CallContext {
    private static final Pattern KEY_FORMAT = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    public CheckoutCallContext {
        Objects.requireNonNull(requestId, "requestId");
        if (idempotencyKey == null || !KEY_FORMAT.matcher(idempotencyKey).matches()) {
            throw new IllegalArgumentException(
                    "Idempotency-Key must contain 1–64 letters, digits, hyphens, or underscores");
        }
    }
}
