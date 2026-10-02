package org.killbill.billing.payment.api;

import org.example.TransactionStatus;
import org.example.TransactionType;
import org.example.TransactionState;
import org.example.plugin.PaymentPluginStatus;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.UUID;

public interface PaymentTransaction {
    UUID id();

    UUID paymentId();

    TransactionType transactionType();

    BigDecimal amount();

    Currency currency();

    TransactionStatus status();

    TransactionState state();

    PaymentPluginStatus gatewayStatus();
}
