package org.killbill.billing.payment.core;

import org.example.CallContext;
import org.example.OperationException;
import org.example.PaymentAPIException;
import org.example.TransactionStatus;
import org.example.TransactionType;
import org.example.persistence.PaymentTransactionModel;
import org.example.plugin.PaymentPluginAPIException;
import org.example.statemachine.PaymentStateContext;
import org.killbill.billing.account.api.Account;
import org.killbill.billing.payment.api.PaymentApiException;
import org.killbill.billing.payment.api.PaymentTransaction;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class PaymentCoreApiTest {
    private static final UUID ACCOUNT_ID = UUID.randomUUID();
    private static final Account ACCOUNT = () -> ACCOUNT_ID;
    private static final UUID METHOD_ID = UUID.randomUUID();
    private static final BigDecimal AMOUNT = new BigDecimal("123.4567");
    private static final Currency CURRENCY = Currency.getInstance("USD");
    private static final CallContext CALL_CONTEXT = new CallContext() {};

    public static void main(String[] args) throws Exception {
        AtomicReference<PaymentStateContext> received = new AtomicReference<>();
        AtomicReference<PaymentTransaction> produced = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        PaymentCoreApi api = new PaymentCoreApi(context -> {
            received.set(context);
            calls.incrementAndGet();
            check(context.getAccountId().equals(ACCOUNT_ID), "Account must be forwarded");
            check(context.getAmount() == AMOUNT && context.getCurrency() == CURRENCY, "Money must be forwarded without conversion");
            check(context.getCallContext() == CALL_CONTEXT, "Call context must be forwarded");
            check(context.getPaymentId() != null, "Every operation must identify its payment");
            if (context.getTransactionType() == TransactionType.CAPTURE) {
                check(context.getPaymentMethodId() == null, "Capture must let the runner resolve the stored payment method");
                context.setPaymentMethodId(METHOD_ID);
            } else {
                check(context.getPaymentMethodId().equals(METHOD_ID), "Payment method must be forwarded");
            }
            PaymentTransactionModel transaction = transaction(context, TransactionStatus.SUCCESS);
            context.setPaymentTransaction(transaction);
            produced.set(transaction);
        });
        check(api.authorize(ACCOUNT, METHOD_ID, AMOUNT, CURRENCY, CALL_CONTEXT) == produced.get(), "Return the runner's transaction");
        check(received.get().getTransactionType() == TransactionType.AUTHORIZE, "Authorize must use AUTHORIZE");
        UUID firstPaymentId = received.get().getPaymentId();
        check(api.authorize(ACCOUNT, METHOD_ID, AMOUNT, CURRENCY, CALL_CONTEXT) == produced.get(), "Return authorization transaction");
        check(received.get().getTransactionType() == TransactionType.AUTHORIZE, "Authorize must use AUTHORIZE");
        check(!received.get().getPaymentId().equals(firstPaymentId), "New payments must have distinct IDs");
        UUID captureId = UUID.randomUUID();
        check(api.capture(ACCOUNT, captureId, AMOUNT, CURRENCY, CALL_CONTEXT) == produced.get(), "Return capture transaction");
        check(received.get().getTransactionType() == TransactionType.CAPTURE
                && received.get().getPaymentId().equals(captureId), "Capture must retain the supplied payment ID");
        check(calls.get() == 3, "Each request must invoke the runner exactly once");

        PaymentCoreApi pending = new PaymentCoreApi(context -> context.setPaymentTransaction(transaction(context, TransactionStatus.UNKNOWN)));
        check(authorize(pending).status() == TransactionStatus.UNKNOWN, "Pending is a valid asynchronous outcome");
        PaymentCoreApi failed = new PaymentCoreApi(context -> context.setPaymentTransaction(transaction(context, TransactionStatus.FAILURE)));
        check(authorize(failed).status() == TransactionStatus.FAILURE, "Return confirmed declines to the business layer");
        expectFailure(() -> authorize(new PaymentCoreApi(context -> {})));

        PaymentApiException userFailure = new PaymentApiException("Payment was declined");
        check(expectFailure(() -> authorize(new PaymentCoreApi(context -> { throw userFailure; }))) == userFailure,
                "Existing user-facing exceptions must retain identity");
        check(expectFailure(() -> authorize(new PaymentCoreApi(context -> {
            throw new OperationException("Workflow failed", userFailure);
        }))) == userFailure, "Unwrap a user-facing API exception inside OperationException");
        IllegalStateException gatewayCause = new IllegalStateException("Gateway unavailable");
        PaymentAPIException legacy = new PaymentAPIException("Payment provider unavailable", gatewayCause);
        PaymentApiException translated = expectFailure(() -> authorize(new PaymentCoreApi(context -> {
            throw new CompletionException(new OperationException("Workflow failed", legacy));
        })));
        check(translated.getMessage().equals(legacy.getMessage()) && translated.getCause() == gatewayCause,
                "Unwrap transport and operation wrappers while preserving API details");
        PaymentApiException operationFailure = expectFailure(() -> authorize(new PaymentCoreApi(context -> {
            throw new OperationException("Internal operation failure", gatewayCause);
        })));
        check(operationFailure.getCause() == gatewayCause, "Preserve the underlying operation failure");
        PaymentPluginAPIException pluginFailure = new PaymentPluginAPIException("Network failure");
        check(authorize(new PaymentCoreApi(context -> {
            context.setException(pluginFailure);
            context.setPaymentTransaction(transaction(context, TransactionStatus.UNKNOWN));
        })).status() == TransactionStatus.UNKNOWN, "Stored gateway exceptions must remain UNKNOWN");

        RuntimeException bug = new IllegalArgumentException("Unexpected bug");
        try {
            authorize(new PaymentCoreApi(context -> { throw bug; }));
            throw new AssertionError("Unexpected runtime failure must propagate");
        } catch (RuntimeException exception) {
            check(exception == bug, "Do not misclassify unrelated programming failures");
        }
        OperationException cyclic = new OperationException("Cyclic operation failure");
        RuntimeException wrapper = new RuntimeException(cyclic);
        cyclic.initCause(wrapper);
        expectFailure(() -> authorize(new PaymentCoreApi(context -> { throw wrapper; })));

        PaymentCoreApi invalid = new PaymentCoreApi(context -> { throw new AssertionError("Invalid input must not reach the runner"); });
        expectFailure(() -> invalid.authorize(null, METHOD_ID, AMOUNT, CURRENCY, CALL_CONTEXT));
        expectFailure(() -> invalid.authorize(() -> null, METHOD_ID, AMOUNT, CURRENCY, CALL_CONTEXT));
        expectFailure(() -> invalid.authorize(ACCOUNT, null, AMOUNT, CURRENCY, CALL_CONTEXT));
        expectFailure(() -> invalid.capture(ACCOUNT, null, AMOUNT, CURRENCY, CALL_CONTEXT));
        for (BigDecimal amount : List.of(BigDecimal.ZERO, BigDecimal.ONE.negate())) {
            expectFailure(() -> invalid.authorize(ACCOUNT, METHOD_ID, amount, CURRENCY, CALL_CONTEXT));
        }
        expectFailure(() -> invalid.authorize(ACCOUNT, METHOD_ID, null, CURRENCY, CALL_CONTEXT));
        expectFailure(() -> invalid.authorize(ACCOUNT, METHOD_ID, AMOUNT, null, CALL_CONTEXT));
        expectFailure(() -> invalid.authorize(ACCOUNT, METHOD_ID, AMOUNT, CURRENCY, null));
        System.out.println("Passed PaymentCoreApi dispatch, return values, capture identity, validation, and exception-unwrapping checks");
    }

    private static PaymentTransactionModel transaction(PaymentStateContext context, TransactionStatus status) {
        return new PaymentTransactionModel(UUID.randomUUID(), context.getAccountId(), context.getPaymentId(),
                context.getTransactionType(), context.getAmount(), context.getCurrency(), status,
                null, null, null, Instant.now(), Instant.now());
    }

    private static PaymentTransaction authorize(PaymentCoreApi api) throws PaymentApiException {
        return api.authorize(ACCOUNT, METHOD_ID, AMOUNT, CURRENCY, CALL_CONTEXT);
    }

    @FunctionalInterface
    private interface ApiCall {
        void run() throws PaymentApiException;
    }

    private static PaymentApiException expectFailure(ApiCall action) throws PaymentApiException {
        try {
            action.run();
            throw new AssertionError("Expected PaymentApiException");
        } catch (PaymentApiException exception) {
            return exception;
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
