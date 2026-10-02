package org.killbill.billing.payment.checkout;

import org.killbill.billing.payment.api.PaymentApiException;

public final class IdempotencyConflictException extends PaymentApiException {
    public IdempotencyConflictException() {
        super("This idempotency key is already associated with different payment details.");
    }
}
