package org.killbill.billing.payment.core;

import org.example.CallContext;
import org.example.OperationException;
import org.example.PaymentAPIException;
import org.example.TransactionStatus;
import org.example.TransactionType;
import org.example.statemachine.PaymentStateContext;
import org.killbill.billing.account.api.Account;
import org.killbill.billing.payment.api.PaymentApiException;
import org.killbill.billing.payment.api.PaymentTransaction;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Currency;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class PaymentCoreApi {
    private final PaymentAutomatonRunner paymentAutomatonRunner;

    public PaymentCoreApi(PaymentAutomatonRunner paymentAutomatonRunner) {
        this.paymentAutomatonRunner = Objects.requireNonNull(paymentAutomatonRunner, "paymentAutomatonRunner");
    }

    private static boolean containsPaymentException(Throwable exception) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = exception; current != null && visited.add(current); current = current.getCause()) {
            if (current instanceof PaymentAPIException || current instanceof OperationException) {
                return true;
            }
        }
        return false;
    }

    private static PaymentApiException toApiException(TransactionType type, Throwable exception) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable rootCause = exception;
        PaymentAPIException paymentException = null;
        for (Throwable current = exception; current != null && visited.add(current); current = current.getCause()) {
            if (current instanceof PaymentApiException apiException) {
                return apiException;
            }
            if (current instanceof PaymentAPIException apiException) {
                paymentException = apiException;
            }
            rootCause = current;
        }
        String message = paymentException == null || paymentException.getMessage() == null
                ? "Unable to complete " + type.name().toLowerCase(java.util.Locale.ROOT) + " operation"
                : paymentException.getMessage();
        return new PaymentApiException(message, rootCause);
    }

    public PaymentTransaction authorize(Account account,
                                        UUID paymentMethodId,
                                        BigDecimal amount,
                                        Currency currency,
                                        CallContext callContext
    ) throws PaymentApiException {
        return authorize(account, UUID.randomUUID(), paymentMethodId, amount, currency, callContext);
    }

    /** Executes authorization against a parent payment already created by the business workflow. */
    public PaymentTransaction authorize(Account account, UUID paymentId, UUID paymentMethodId,
                                        BigDecimal amount, Currency currency, CallContext callContext)
            throws PaymentApiException {
        if (paymentId == null) {
            throw new PaymentApiException("A payment ID is required for authorization");
        }
        PaymentStateContext context = newContext(account,
                paymentMethodId,
                amount,
                currency,
                TransactionType.AUTHORIZE,
                callContext
        );
        context.setPaymentId(paymentId);
        return execute(context);
    }

    public PaymentTransaction capture(Account account,
                                      UUID paymentId,
                                      BigDecimal amount,
                                      Currency currency,
                                      CallContext callContext
    ) throws PaymentApiException {
        return capture(account, paymentId, null, amount, currency, callContext);
    }

    /** Uses the method on the parent payment; the legacy overload delegates method resolution to the runner. */
    public PaymentTransaction capture(Account account, UUID paymentId, UUID paymentMethodId,
                                      BigDecimal amount, Currency currency, CallContext callContext)
            throws PaymentApiException {
        if (paymentId == null) {
            throw new PaymentApiException("A payment ID is required for capture");
        }
        PaymentStateContext context = newContext(account, paymentMethodId, amount, currency, TransactionType.CAPTURE, callContext);
        context.setPaymentId(paymentId);
        return execute(context);
    }

    private PaymentStateContext newContext(Account account,
                                           UUID paymentMethodId,
                                           BigDecimal amount,
                                           Currency currency,
                                           TransactionType transactionType,
                                           CallContext callContext
    ) throws PaymentApiException {
        if (account == null || account.getId() == null) {
            throw new PaymentApiException("An account ID is required");
        }
        if (paymentMethodId == null && transactionType != TransactionType.CAPTURE) {
            throw new PaymentApiException("A payment method ID is required");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new PaymentApiException("The payment amount must be greater than zero");
        }
        if (currency == null || callContext == null) {
            throw new PaymentApiException("Currency and call context are required");
        }
        return new PaymentStateContext(account.getId(), paymentMethodId, amount, currency, transactionType, callContext);
    }

    private PaymentTransaction execute(PaymentStateContext context) throws PaymentApiException {
        try {
            paymentAutomatonRunner.run(context);
        } catch (OperationException | PaymentAPIException exception) {
            throw toApiException(context.getTransactionType(), exception);
        } catch (RuntimeException exception) {
            if (!containsPaymentException(exception)) {
                throw exception;
            }
            throw toApiException(context.getTransactionType(), exception);
        }

        PaymentTransaction transaction = context.getPaymentTransaction();
        if (transaction == null || transaction.status() == null) {
            throw new PaymentApiException("The payment workflow did not return a transaction");
        }
        // A recorded gateway exception is an unresolved outcome, not a confirmed decline.
        // Exceptions thrown by the runner (including persistence failures) still propagate above.
        if (context.hasException() && transaction.status() != TransactionStatus.UNKNOWN) {
            throw toApiException(context.getTransactionType(), context.getException());
        }
        return transaction;
    }
}
