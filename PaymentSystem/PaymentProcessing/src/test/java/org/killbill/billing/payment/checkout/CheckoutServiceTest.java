package org.killbill.billing.payment.checkout;

import org.example.Payment;
import org.example.persistence.FilePaymentRepository;
import org.example.persistence.PaymentRepository;
import org.example.persistence.PaymentStorageException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.Optional;
import org.example.PaymentState;
import org.example.PaymentStage;
import org.example.CallContext;
import org.example.PaymentAPIException;
import org.example.TransactionStatus;
import org.example.TransactionType;
import org.example.persistence.PaymentTransactionModel;
import org.example.plugin.PaymentPluginAPIException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.killbill.billing.payment.api.PaymentApiException;
import org.killbill.billing.payment.core.PaymentCoreApi;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CheckoutServiceTest {
    @TempDir
    Path directory;
    private FilePaymentRepository repository;

    @BeforeEach
    void openRepository() {
        repository = new FilePaymentRepository(directory.resolve("payments.json"));
    }

    @AfterEach
    void closeRepository() {
        repository.close();
    }

    private final UUID accountId = UUID.randomUUID();
    private final UUID methodId = UUID.randomUUID();
    private final BigDecimal amount = new BigDecimal("12.34");
    private final Currency currency = Currency.getInstance("USD");
    private final CallContext callContext = new CallContext() {};

    @ParameterizedTest
    @CsvSource({
            "SUCCESS, SUCCESS, SUCCEEDED, CAPTURE",
            "FAILURE, SUCCESS, FAILED, AUTHORIZATION",
            "UNKNOWN, SUCCESS, UNKNOWN, AUTHORIZATION",
            "SUCCESS, FAILURE, FAILED, CAPTURE",
            "SUCCESS, UNKNOWN, UNKNOWN, CAPTURE"
    })
    void sequencesOnlyConfirmedAuthorization(TransactionStatus authorizationStatus, TransactionStatus captureStatus,
                                           PaymentState expectedStatus, PaymentStage expectedStage) throws Exception {
        List<PaymentTransactionModel> transactions = new ArrayList<>();
        PaymentCoreApi core = new PaymentCoreApi(context -> {
            assertEquals(accountId, context.getAccountId());
            assertEquals(amount, context.getAmount());
            assertEquals(currency, context.getCurrency());
            assertSame(callContext, context.getCallContext());
            Payment saved = repository.findById(context.getPaymentId()).orElseThrow();
            assertEquals(PaymentState.PENDING, saved.getPaymentState());
            assertEquals(context.getTransactionType() == TransactionType.AUTHORIZE
                    ? PaymentStage.AUTHORIZATION : PaymentStage.CAPTURE, saved.getPaymentStage());
            assertEquals(accountId, saved.getAccountId());
            assertEquals(methodId, saved.getPaymentMethodId());
            assertEquals(methodId, context.getPaymentMethodId());
            TransactionStatus outcome;
            switch (context.getTransactionType()) {
                case AUTHORIZE -> {
                    assertTrue(transactions.isEmpty());
                    assertEquals(methodId, context.getPaymentMethodId());
                    outcome = authorizationStatus;
                }
                case CAPTURE -> {
                    assertEquals(1, transactions.size());
                    assertEquals(TransactionStatus.SUCCESS, transactions.getFirst().status());
                    assertEquals(transactions.getFirst().paymentId(), context.getPaymentId());
                    context.setPaymentMethodId(methodId);
                    outcome = captureStatus;
                }
                default -> throw new AssertionError("Checkout must only request AUTHORIZE and CAPTURE");
            }
            if (outcome == TransactionStatus.UNKNOWN) {
                context.setException(new PaymentPluginAPIException("Gateway response timed out"));
            }
            PaymentTransactionModel transaction = new PaymentTransactionModel(UUID.randomUUID(), accountId,
                    context.getPaymentId(), context.getTransactionType(), amount, currency, outcome,
                    null, null, null, Instant.now(), Instant.now());
            repository.createTransaction(transaction);
            transactions.add(transaction);
            context.setPaymentTransaction(transaction);
        });

        CheckoutResult result = new CheckoutService(core, repository).checkout(() -> accountId, methodId, amount, currency, callContext);
        assertEquals(1, repository.findAll().size());
        Payment stored = repository.findById(result.paymentId()).orElseThrow();
        assertEquals(expectedStatus, stored.getPaymentState());
        assertEquals(expectedStage, stored.getPaymentStage());
        repository.close();
        repository = new FilePaymentRepository(directory.resolve("payments.json"));
        assertEquals(expectedStatus, repository.findById(result.paymentId()).orElseThrow().getPaymentState());
        assertEquals(expectedStage, repository.findById(result.paymentId()).orElseThrow().getPaymentStage());
        assertEquals(transactions, repository.findTransactionsByPaymentId(result.paymentId()));
        assertEquals(expectedStatus, result.status());
        assertEquals(expectedStage, result.stage());
        assertEquals(transactions.getFirst().paymentId(), result.paymentId());
        assertEquals(transactions.getFirst().id(), result.authorizationTransactionId());
        switch (expectedStage) {
            case CREATED -> throw new AssertionError("Checkout result must identify an attempted operation");
            case AUTHORIZATION -> {
                assertEquals(1, transactions.size());
                assertNull(result.captureTransactionId());
            }
            case CAPTURE -> {
                assertEquals(2, transactions.size());
                assertEquals(transactions.getLast().id(), result.captureTransactionId());
                assertNotEquals(result.authorizationTransactionId(), result.captureTransactionId());
                assertEquals(TransactionStatus.SUCCESS, transactions.getFirst().status());
            }
        }
    }

    @Test
    void savesParentAndStagesBeforeCallingOperations() throws Exception {
        List<String> events = new ArrayList<>();
        PaymentRepository recording = new ForwardingRepository() {
            @Override
            public void create(Payment payment) {
                events.add("CREATE:" + payment.getPaymentStage() + ":" + payment.getPaymentState());
                super.create(payment);
            }

            @Override
            public void update(Payment payment) {
                events.add("UPDATE:" + payment.getPaymentStage() + ":" + payment.getPaymentState());
                super.update(payment);
            }
        };
        PaymentCoreApi core = new PaymentCoreApi(context -> {
            events.add("RUN:" + context.getTransactionType());
            Payment parent = repository.findById(context.getPaymentId()).orElseThrow();
            assertEquals(parent.getPaymentMethodId(), context.getPaymentMethodId());
            context.setPaymentTransaction(success(context));
        });
        new CheckoutService(core, recording).checkout(() -> accountId, methodId, amount, currency, callContext);
        assertEquals(List.of("CREATE:CREATED:PENDING", "UPDATE:AUTHORIZATION:PENDING", "RUN:AUTHORIZE",
                "UPDATE:CAPTURE:PENDING", "RUN:CAPTURE", "UPDATE:CAPTURE:SUCCEEDED"), events);
    }

    @ParameterizedTest
    @CsvSource({"CREATE, 0, CREATED", "AUTHORIZATION, 0, CREATED", "CAPTURE, 1, AUTHORIZATION", "OUTCOME, 2, CAPTURE"})
    void storageFailuresStopProgressAndNeverReturnSuccess(String failurePoint, int expectedCalls,
                                                         PaymentStage savedStage) {
        List<TransactionType> calls = new ArrayList<>();
        PaymentStorageException failure = new PaymentStorageException("Disk unavailable", null);
        PaymentRepository failing = new ForwardingRepository() {
            @Override
            public void create(Payment payment) {
                if (failurePoint.equals("CREATE")) {
                    throw failure;
                }
                super.create(payment);
            }

            @Override
            public void update(Payment payment) {
                if (failurePoint.equals(payment.getPaymentStage().name())
                        || failurePoint.equals("OUTCOME") && payment.getPaymentState() == PaymentState.SUCCEEDED) {
                    throw failure;
                }
                super.update(payment);
            }
        };
        PaymentCoreApi core = new PaymentCoreApi(context -> {
            calls.add(context.getTransactionType());
            context.setPaymentTransaction(success(context));
        });
        PaymentApiException error = assertThrows(PaymentApiException.class,
                () -> new CheckoutService(core, failing).checkout(() -> accountId, methodId, amount, currency, callContext));
        assertSame(failure, error.getCause());
        assertEquals(expectedCalls, calls.size());
        if (failurePoint.equals("CREATE")) {
            assertTrue(repository.findAll().isEmpty());
        } else {
            assertEquals(savedStage, repository.findAll().getFirst().getPaymentStage());
            assertEquals(PaymentState.PENDING, repository.findAll().getFirst().getPaymentState());
        }
    }

    @Test
    void preservesOperationErrorWhenSavingUnknownAlsoFails() {
        PaymentApiException original = new PaymentApiException("Operation outcome unavailable");
        PaymentStorageException diskFailure = new PaymentStorageException("Disk unavailable", null);
        PaymentRepository failing = new ForwardingRepository() {
            @Override
            public void update(Payment payment) {
                if (payment.getPaymentState() == PaymentState.UNKNOWN) {
                    throw diskFailure;
                }
                super.update(payment);
            }
        };
        PaymentCoreApi core = new PaymentCoreApi(context -> { throw original; });
        PaymentApiException error = assertThrows(PaymentApiException.class,
                () -> new CheckoutService(core, failing).checkout(() -> accountId, methodId, amount, currency, callContext));
        assertSame(original, error);
        assertEquals(1, error.getSuppressed().length);
        assertSame(diskFailure, error.getSuppressed()[0].getCause());
        assertEquals(PaymentState.PENDING, repository.findAll().getFirst().getPaymentState());
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = TransactionType.class, names = {"AUTHORIZE", "CAPTURE"})
    void rejectsAnOperationReturningAnotherPayment(TransactionType wrongOperation) {
        List<TransactionType> calls = new ArrayList<>();
        PaymentCoreApi core = new PaymentCoreApi(context -> {
            calls.add(context.getTransactionType());
            PaymentTransactionModel valid = success(context);
            context.setPaymentTransaction(context.getTransactionType() == wrongOperation
                    ? new PaymentTransactionModel(valid.id(), valid.accountId(), UUID.randomUUID(), valid.transactionType(),
                    valid.amount(), valid.currency(), valid.status(), null, null, null, valid.createdAt(), valid.processedAt())
                    : valid);
        });
        assertThrows(PaymentApiException.class,
                () -> new CheckoutService(core, repository).checkout(() -> accountId, methodId, amount, currency, callContext));
        assertEquals(wrongOperation == TransactionType.AUTHORIZE ? 1 : 2, calls.size());
        assertEquals(PaymentState.UNKNOWN, repository.findAll().getFirst().getPaymentState());
    }

    @Test
    void invalidInputCreatesNoPaymentAndCallsNoOperation() {
        CheckoutService service = new CheckoutService(new PaymentCoreApi(context -> fail("Unexpected operation")), repository);
        assertThrows(PaymentApiException.class, () -> service.checkout(null, methodId, amount, currency, callContext));
        assertThrows(PaymentApiException.class, () -> service.checkout(() -> null, methodId, amount, currency, callContext));
        assertThrows(PaymentApiException.class, () -> service.checkout(() -> accountId, null, amount, currency, callContext));
        assertThrows(PaymentApiException.class, () -> service.checkout(() -> accountId, methodId, null, currency, callContext));
        assertThrows(PaymentApiException.class, () -> service.checkout(() -> accountId, methodId, BigDecimal.ZERO, currency, callContext));
        assertThrows(PaymentApiException.class, () -> service.checkout(() -> accountId, methodId, amount, null, callContext));
        assertThrows(PaymentApiException.class, () -> service.checkout(() -> accountId, methodId, amount, currency, null));
        assertTrue(repository.findAll().isEmpty());
    }

    private PaymentTransactionModel success(org.example.statemachine.PaymentStateContext context) {
        return new PaymentTransactionModel(UUID.randomUUID(), context.getAccountId(), context.getPaymentId(),
                context.getTransactionType(), context.getAmount(), context.getCurrency(), TransactionStatus.SUCCESS,
                null, null, null, Instant.now(), Instant.now());
    }

    private class ForwardingRepository implements PaymentRepository {
        @Override
        public org.example.persistence.PaymentRegistration createOrFind(Payment payment) {
            return repository.createOrFind(payment);
        }

        @Override
        public Optional<Payment> findByIdempotencyKey(String key) {
            return repository.findByIdempotencyKey(key);
        }

        @Override
        public void create(Payment payment) {
            repository.create(payment);
        }

        @Override
        public Optional<Payment> findById(UUID id) {
            return repository.findById(id);
        }

        @Override
        public List<Payment> findAll() {
            return repository.findAll();
        }

        @Override
        public void update(Payment payment) {
            repository.update(payment);
        }
    }

    @Test
    void persistenceFailureAbortsCheckout() {
        List<TransactionType> calls = new ArrayList<>();
        PaymentCoreApi core = new PaymentCoreApi(context -> {
            calls.add(context.getTransactionType());
            throw new PaymentAPIException("Unable to persist payment transaction intent", null);
        });
        assertThrows(PaymentApiException.class,
                () -> new CheckoutService(core, repository).checkout(() -> accountId, methodId, amount, currency, callContext));
        assertEquals(List.of(TransactionType.AUTHORIZE), calls);
        assertEquals(PaymentState.UNKNOWN, repository.findAll().getFirst().getPaymentState());
        assertEquals(PaymentStage.AUTHORIZATION, repository.findAll().getFirst().getPaymentStage());
    }

    @Test
    void capturePersistenceFailureDoesNotProduceCheckoutSuccess() {
        List<TransactionType> calls = new ArrayList<>();
        PaymentCoreApi core = new PaymentCoreApi(context -> {
            calls.add(context.getTransactionType());
            if (context.getTransactionType() == TransactionType.CAPTURE) {
                throw new PaymentAPIException("Unable to persist payment transaction outcome", null);
            }
            context.setPaymentTransaction(new PaymentTransactionModel(UUID.randomUUID(), accountId,
                    context.getPaymentId(), TransactionType.AUTHORIZE, amount, currency, TransactionStatus.SUCCESS,
                    null, null, null, Instant.now(), Instant.now()));
        });
        assertThrows(PaymentApiException.class,
                () -> new CheckoutService(core, repository).checkout(() -> accountId, methodId, amount, currency, callContext));
        assertEquals(List.of(TransactionType.AUTHORIZE, TransactionType.CAPTURE), calls);
        assertEquals(PaymentState.UNKNOWN, repository.findAll().getFirst().getPaymentState());
        assertEquals(PaymentStage.CAPTURE, repository.findAll().getFirst().getPaymentStage());
    }
}
