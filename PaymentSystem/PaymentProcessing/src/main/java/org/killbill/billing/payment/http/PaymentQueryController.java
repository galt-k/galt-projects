package org.killbill.billing.payment.http;

import org.example.PaymentStage;
import org.example.PaymentState;
import org.example.TransactionState;
import org.example.TransactionStatus;
import org.example.TransactionType;
import org.example.persistence.PaymentRepository;
import org.example.persistence.PaymentStorageException;
import org.example.persistence.PaymentTransactionRepository;
import org.example.plugin.PaymentPluginStatus;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/payments")
public class PaymentQueryController {
    private static final System.Logger LOGGER = System.getLogger(PaymentQueryController.class.getName());
    private final PaymentRepository payments;
    private final PaymentTransactionRepository transactions;

    public PaymentQueryController(PaymentRepository payments, PaymentTransactionRepository transactions) {
        this.payments = payments;
        this.transactions = transactions;
    }

    @GetMapping("/{paymentId}")
    public PaymentView find(@PathVariable("paymentId") UUID paymentId) {
        var payment = payments.findById(paymentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found"));
        var attempts = transactions.findTransactionsByPaymentId(paymentId).stream()
                .map(transaction -> new TransactionView(transaction.id(), transaction.transactionType(),
                        transaction.amount(), transaction.currency(), transaction.status(), transaction.state(),
                        transaction.gatewayStatus(), transaction.createdAt(), transaction.processedAt()))
                .toList();
        return new PaymentView(payment.getPaymentId(), payment.getAccountId(), payment.getPaymentMethodId(),
                payment.getAmount(), payment.getCurrency(), payment.getPaymentState(), payment.getPaymentStage(), attempts);
    }

    @ExceptionHandler(PaymentStorageException.class)
    public ProblemDetail storageError(PaymentStorageException exception) {
        LOGGER.log(System.Logger.Level.ERROR, "Payment read failed", exception);
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to read payment");
    }

    public record PaymentView(UUID paymentId, UUID accountId, UUID paymentMethodId, BigDecimal amount,
                              Currency currency, PaymentState status, PaymentStage stage,
                              List<TransactionView> transactions) {}

    public record TransactionView(UUID transactionId, TransactionType type, BigDecimal amount, Currency currency,
                                  TransactionStatus status, TransactionState state, PaymentPluginStatus gatewayStatus,
                                  Instant createdAt, Instant processedAt) {}
}
