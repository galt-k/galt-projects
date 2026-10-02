package org.killbill.billing.payment.checkout;

import org.example.CallContext;
import org.example.PaymentState;
import org.example.TransactionStatus;
import org.example.persistence.FilePaymentRepository;
import org.example.persistence.PaymentRepository;
import org.example.persistence.PaymentTransactionModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.killbill.billing.payment.core.PaymentAutomatonRunner;
import org.killbill.billing.payment.core.PaymentCoreApi;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Currency;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PaymentRepositoryConfigurationTest {
    @TempDir
    Path directory;

    @Test
    void injectsConfiguredRepositoryAndReleasesItsLockOnShutdown() throws Exception {
        Path file = directory.resolve("configured.json");
        UUID accountId = UUID.randomUUID();
        UUID paymentId;
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(
                    new MapPropertySource("test", Map.of("payment.store.path", file.toString())));
            context.registerBean(PaymentAutomatonRunner.class, () -> state ->
                    state.setPaymentTransaction(new PaymentTransactionModel(UUID.randomUUID(), state.getAccountId(),
                            state.getPaymentId(), state.getTransactionType(), state.getAmount(), state.getCurrency(),
                            TransactionStatus.SUCCESS, null, null, null, Instant.now(), Instant.now())));
            context.register(PaymentRepositoryConfiguration.class, PaymentCoreApi.class, CheckoutService.class,
                    org.killbill.billing.payment.core.AuthorizationRunnerConfiguration.class);
            context.refresh();
            var result = context.getBean(CheckoutService.class).checkout(() -> accountId, UUID.randomUUID(),
                    BigDecimal.TEN, Currency.getInstance("USD"), new CallContext() {});
            paymentId = result.paymentId();
            assertEquals(PaymentState.SUCCEEDED, context.getBean(PaymentRepository.class)
                    .findById(paymentId).orElseThrow().getPaymentState());
        }
        try (var restored = new FilePaymentRepository(file)) {
            assertEquals(PaymentState.SUCCEEDED, restored.findById(paymentId).orElseThrow().getPaymentState());
        }
    }
}
