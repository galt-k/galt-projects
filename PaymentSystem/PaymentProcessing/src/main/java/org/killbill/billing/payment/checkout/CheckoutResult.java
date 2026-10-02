package org.killbill.billing.payment.checkout;

import org.example.PaymentState;
import org.example.PaymentStage;
import java.util.Objects;
import java.util.UUID;

public record CheckoutResult(UUID paymentId,
                             PaymentState status,
                             PaymentStage stage,
                             UUID authorizationTransactionId,
                             UUID captureTransactionId) {
    public CheckoutResult {
        Objects.requireNonNull(paymentId, "paymentId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(stage, "stage");
        if (stage != PaymentStage.CAPTURE && captureTransactionId != null) {
            throw new IllegalArgumentException("A capture transaction requires the capture stage");
        }
        // A duplicate may arrive after parent creation but before either transaction intent is saved.
        if (status == PaymentState.SUCCEEDED && (stage != PaymentStage.CAPTURE
                || authorizationTransactionId == null || captureTransactionId == null)) {
            throw new IllegalArgumentException("Checkout succeeds only after capture");
        }
    }
}
