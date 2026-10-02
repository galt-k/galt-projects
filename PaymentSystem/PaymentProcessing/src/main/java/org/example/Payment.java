package org.example;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;

public class Payment {
    private final UUID paymentId;
    private final UUID accountId;
    private final UUID paymentMethodId;
    private final BigDecimal amount;
    private final Currency currency;
    private final String idempotencyKey;
    private PaymentState paymentState = PaymentState.PENDING;
    private PaymentStage paymentStage = PaymentStage.CREATED;

    public Payment(UUID paymentId, UUID accountId, UUID paymentMethodId,
                   BigDecimal amount, Currency currency) {
        this(paymentId, accountId, paymentMethodId, amount, currency, null);
    }

    public Payment(UUID paymentId, UUID accountId, UUID paymentMethodId,
                   BigDecimal amount, Currency currency, String idempotencyKey) {
        this.paymentId = Objects.requireNonNull(paymentId, "paymentId");
        this.accountId = Objects.requireNonNull(accountId, "accountId");
        this.paymentMethodId = Objects.requireNonNull(paymentMethodId, "paymentMethodId");
        this.amount = Objects.requireNonNull(amount, "amount");
        this.currency = Objects.requireNonNull(currency, "currency");
        if (idempotencyKey != null && !idempotencyKey.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("Invalid payment idempotency key");
        }
        this.idempotencyKey = idempotencyKey;
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("The payment amount must be greater than zero");
        }
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public UUID getPaymentMethodId() {
        return paymentMethodId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public Currency getCurrency() {
        return currency;
    }

    public PaymentState getPaymentState() {
        return paymentState;
    }

    public void setPaymentState(PaymentState paymentState) {
        this.paymentState = Objects.requireNonNull(paymentState, "paymentState");
    }

    public PaymentStage getPaymentStage() {
        return paymentStage;
    }

    public void setPaymentStage(PaymentStage paymentStage) {
        this.paymentStage = Objects.requireNonNull(paymentStage, "paymentStage");
    }
}
