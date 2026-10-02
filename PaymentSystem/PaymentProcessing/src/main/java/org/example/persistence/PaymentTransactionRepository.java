package org.example.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentTransactionRepository {
    void createTransaction(PaymentTransactionModel transaction);

    Optional<PaymentTransactionModel> findTransactionById(UUID transactionId);

    List<PaymentTransactionModel> findTransactionsByPaymentId(UUID paymentId);

    void updateTransaction(PaymentTransactionModel transaction);
}
