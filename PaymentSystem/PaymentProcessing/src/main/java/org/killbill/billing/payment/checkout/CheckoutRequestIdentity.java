package org.killbill.billing.payment.checkout;

import org.example.Payment;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;

/** Business fields compared when an existing checkout idempotency key is reused. */
public record CheckoutRequestIdentity(UUID accountId, UUID paymentMethodId, BigDecimal amount, Currency currency) {
    public CheckoutRequestIdentity {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(paymentMethodId, "paymentMethodId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("The payment amount must be greater than zero");
        }
        // Normalize only the comparison value; do not round or change the stored payment amount.
        amount = amount.stripTrailingZeros();
    }

    public static CheckoutRequestIdentity from(Payment payment) {
        Objects.requireNonNull(payment, "payment");
        return new CheckoutRequestIdentity(payment.getAccountId(), payment.getPaymentMethodId(),
                payment.getAmount(), payment.getCurrency());
    }
}
