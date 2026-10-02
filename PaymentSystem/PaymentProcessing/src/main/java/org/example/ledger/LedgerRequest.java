package org.example.ledger;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;

public record LedgerRequest(UUID accountId, UUID paymentId, UUID transactionId, BigDecimal amount, Currency currency) {
    public LedgerRequest {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(paymentId, "paymentId");
        Objects.requireNonNull(transactionId, "transactionId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("Ledger amount must be positive");
        }
    }
}
