package org.example;

public record PaymentItem(int batchId, int id, String sourceAccount, String destinationAccount, long amount ) {};