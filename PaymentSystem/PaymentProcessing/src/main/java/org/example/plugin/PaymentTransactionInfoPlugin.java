package org.example.plugin;

public record PaymentTransactionInfoPlugin(String gatewayTransactionId,
                                           String gatewayResponseCode,
                                           String gatewayErrorMessage) {}
