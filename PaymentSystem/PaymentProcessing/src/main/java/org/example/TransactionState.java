package org.example;

import java.util.Objects;

public enum TransactionState {
    AUTHORIZATION_REQUESTED,
    AUTHORIZATION_COMPLETED,
    CAPTURE_REQUESTED,
    CAPTURE_COMPLETED,
    CHECKOUT_REQUESTED,
    CHECKOUT_COMPLETED;

    public static TransactionState forOutcome(TransactionType type, TransactionStatus status) {
        Objects.requireNonNull(type, "transactionType");
        Objects.requireNonNull(status, "status");
        boolean completed = status != TransactionStatus.UNKNOWN;
        return switch (type) {
            case AUTHORIZE -> completed ? AUTHORIZATION_COMPLETED : AUTHORIZATION_REQUESTED;
            case CAPTURE -> completed ? CAPTURE_COMPLETED : CAPTURE_REQUESTED;
            case PURCHASE, CHECKOUT -> completed ? CHECKOUT_COMPLETED : CHECKOUT_REQUESTED;
        };
    }
}
