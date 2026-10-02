package org.killbill.billing.payment.core;

import org.example.OperationException;
import org.example.Payment;
import org.example.TransactionStatus;
import org.example.TransactionType;
import org.example.callbacks.AuthorizeOperation;
import org.example.callbacks.CaptureOperation;
import org.example.ledger.LedgerStatus;
import org.example.ledger.MockLedgerServer;
import org.example.ledger.RestLedgerClient;
import org.example.persistence.FilePaymentRepository;
import org.example.persistence.PaymentTransactionModel;
import org.example.persistence.PaymentWorkflowDAOHelper;
import org.example.plugin.MockAuthorizationPlugin;
import org.example.statemachine.PaymentStateContext;
import org.example.states.Created;
import org.example.workflow.OperationCallback;
import org.example.workflow.OperationResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.killbill.billing.payment.checkout.CheckoutCallContext;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Currency;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class OperationIdempotencyKeyTest {
    @TempDir Path directory;

    @Test
    void callbacksReuseSavedTransactionIdsAfterPaymentStoreReopens() throws Exception {
        var plugin = new MockAuthorizationPlugin(MockAuthorizationPlugin.Outcome.SUCCESS);
        var parent = new Payment(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                BigDecimal.TEN, Currency.getInstance("USD"), "parent-checkout-key");
        Path file = directory.resolve("payments.json");
        UUID authorization;
        UUID capture;
        try (var ledger = new MockLedgerServer(0, LedgerStatus.SUCCESS);
             var client = new RestLedgerClient(ledger.endpoint(), Duration.ofSeconds(2))) {
            try (var store = new FilePaymentRepository(file)) {
                store.create(parent);
                var dao = new PaymentWorkflowDAOHelper(store, "mock", Clock.systemUTC());
                var authIntent = dao.insertPaymentTransaction(parent.getAccountId(), parent.getPaymentId(),
                        TransactionType.AUTHORIZE, parent.getAmount(), parent.getCurrency(), TransactionStatus.UNKNOWN);
                var captureIntent = dao.insertPaymentTransaction(parent.getAccountId(), parent.getPaymentId(),
                        TransactionType.CAPTURE, parent.getAmount(), parent.getCurrency(), TransactionStatus.UNKNOWN);
                authorization = authIntent.id();
                capture = captureIntent.id();
                assertNotEquals(authorization, capture);
                assertEquals(OperationResult.SUCCESS, new AuthorizeOperation(dao, context(parent, authIntent), plugin).doOperation(Created.INSTANCE));
                assertEquals(OperationResult.SUCCESS, new CaptureOperation(dao, context(parent, captureIntent), plugin, client).doOperation(Created.INSTANCE));
                // Leave UNKNOWN intents on disk to model losing each downstream response before outcome persistence.
            }
            try (var reopened = new FilePaymentRepository(file)) {
                var dao = new PaymentWorkflowDAOHelper(reopened, "mock", Clock.systemUTC());
                var authIntent = reopened.findTransactionById(authorization).orElseThrow();
                var captureIntent = reopened.findTransactionById(capture).orElseThrow();
                assertEquals(OperationResult.SUCCESS, new AuthorizeOperation(dao, context(parent, authIntent), plugin).doOperation(Created.INSTANCE));
                assertEquals(OperationResult.SUCCESS, new CaptureOperation(dao, context(parent, captureIntent), plugin, client).doOperation(Created.INSTANCE));
                assertEquals(2, reopened.findTransactionsByPaymentId(parent.getPaymentId()).size());
                assertEquals(2, plugin.authorizationCalls());
                assertEquals(1, plugin.holds());
                assertEquals(2, ledger.requests().size());
                assertEquals(1, ledger.entries().size());
                assertEquals(capture, ledger.entries().getFirst().transactionId());

                for (TransactionType type : new TransactionType[]{TransactionType.AUTHORIZE, TransactionType.CAPTURE}) {
                    var invalid = new PaymentStateContext(parent.getAccountId(), parent.getPaymentMethodId(),
                            parent.getAmount(), parent.getCurrency(), type,
                            new CheckoutCallContext(UUID.randomUUID(), parent.getIdempotencyKey()));
                    invalid.setPaymentId(parent.getPaymentId());
                    OperationCallback callback = type == TransactionType.AUTHORIZE ? new AuthorizeOperation(dao, invalid, plugin)
                            : new CaptureOperation(dao, invalid, plugin, client);
                    assertThrows(OperationException.class, () -> callback.doOperation(Created.INSTANCE));
                    invalid.setPaymentTransaction(type == TransactionType.AUTHORIZE ? authIntent : captureIntent);
                    invalid.setAmount(BigDecimal.ONE);
                    assertThrows(OperationException.class, () -> callback.doOperation(Created.INSTANCE));
                }
                assertEquals(2, plugin.authorizationCalls());
                assertEquals(2, ledger.requests().size());
            }
        }
    }

    private PaymentStateContext context(Payment parent, PaymentTransactionModel transaction) {
        var context = new PaymentStateContext(parent.getAccountId(), parent.getPaymentMethodId(), parent.getAmount(),
                parent.getCurrency(), transaction.transactionType(), new CheckoutCallContext(UUID.randomUUID(), parent.getIdempotencyKey()));
        context.setPaymentId(parent.getPaymentId());
        context.setPaymentTransaction(transaction);
        return context;
    }
}
