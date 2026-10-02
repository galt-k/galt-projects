package org.killbill.billing.payment.checkout;

import org.example.CallContext;
import org.example.Payment;
import org.example.PaymentStage;
import org.example.PaymentState;
import org.example.TransactionType;
import org.example.persistence.PaymentRepository;
import org.example.persistence.PaymentRegistration;
import org.killbill.billing.account.api.Account;
import org.killbill.billing.payment.api.PaymentApiException;
import org.killbill.billing.payment.api.PaymentTransaction;
import org.killbill.billing.payment.core.PaymentCoreApi;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;

@Service
public class CheckoutService {
    private final PaymentCoreApi paymentCoreApi;
    private final PaymentRepository paymentRepository;

    public CheckoutService(PaymentCoreApi paymentCoreApi, PaymentRepository paymentRepository) {
        this.paymentCoreApi = Objects.requireNonNull(paymentCoreApi, "paymentCoreApi");
        this.paymentRepository = Objects.requireNonNull(paymentRepository, "paymentRepository");
    }

    public CheckoutResult checkout(Account account, UUID paymentMethodId, BigDecimal amount,
                                   Currency currency, CallContext callContext) throws PaymentApiException {
        validateRequest(account, paymentMethodId, amount, currency, callContext);
        String key = callContext instanceof CheckoutCallContext checkoutContext ? checkoutContext.idempotencyKey() : null;
        Payment payment = new Payment(UUID.randomUUID(), account.getId(), paymentMethodId, amount, currency, key);
        PaymentRegistration registration = null;
        try {
            if (key == null) {
                paymentRepository.create(payment);
            } else {
                registration = paymentRepository.createOrFind(payment);
            }
        } catch (RuntimeException exception) {
            throw new PaymentApiException("Unable to create parent payment " + payment.getPaymentId(), exception);
        }

        if (registration != null && !registration.created()) {
            if (!CheckoutRequestIdentity.from(payment).equals(CheckoutRequestIdentity.from(registration.payment()))) {
                throw new IdempotencyConflictException();
            }
            return existingResult(registration);
        }

        saveProgress(payment, PaymentStage.AUTHORIZATION, PaymentState.PENDING);
        PaymentTransaction authorization = execute(payment, TransactionType.AUTHORIZE, null,
                () -> paymentCoreApi.authorize(account, payment.getPaymentId(), payment.getPaymentMethodId(),
                        payment.getAmount(), payment.getCurrency(), callContext));

        return switch (authorization.status()) {
            case SUCCESS -> capture(account, payment, authorization, callContext);
            case FAILURE -> complete(payment, PaymentState.FAILED, authorization.id(), null);
            case UNKNOWN -> complete(payment, PaymentState.UNKNOWN, authorization.id(), null);
        };
    }

    private static CheckoutResult existingResult(PaymentRegistration registration) {
        UUID authorizationId = null;
        UUID captureId = null;
        for (var transaction : registration.transactions()) {
            switch (transaction.transactionType()) {
                case AUTHORIZE -> authorizationId = transaction.id();
                case CAPTURE -> captureId = transaction.id();
                case PURCHASE, CHECKOUT -> { }
            }
        }
        Payment payment = registration.payment();
        return new CheckoutResult(payment.getPaymentId(), payment.getPaymentState(), payment.getPaymentStage(),
                authorizationId, captureId);
    }

    private CheckoutResult capture(Account account, Payment payment, PaymentTransaction authorization,
                                   CallContext callContext) throws PaymentApiException {
        saveProgress(payment, PaymentStage.CAPTURE, PaymentState.PENDING);
        PaymentTransaction capture = execute(payment, TransactionType.CAPTURE, authorization.id(),
                () -> paymentCoreApi.capture(account, payment.getPaymentId(), payment.getPaymentMethodId(),
                        payment.getAmount(), payment.getCurrency(), callContext));
        PaymentState state = switch (capture.status()) {
            case SUCCESS -> PaymentState.SUCCEEDED;
            case FAILURE -> PaymentState.FAILED;
            case UNKNOWN -> PaymentState.UNKNOWN;
        };
        return complete(payment, state, authorization.id(), capture.id());
    }

    private CheckoutResult complete(Payment payment, PaymentState state, UUID authorizationId, UUID captureId)
            throws PaymentApiException {
        saveProgress(payment, payment.getPaymentStage(), state);
        return new CheckoutResult(payment.getPaymentId(), payment.getPaymentState(), payment.getPaymentStage(),
                authorizationId, captureId);
    }

    private PaymentTransaction execute(Payment payment, TransactionType type, UUID authorizationId,
                                       PaymentOperation operation) throws PaymentApiException {
        try {
            PaymentTransaction transaction = operation.run();
            if (transaction == null || transaction.id() == null || transaction.status() == null
                    || transaction.transactionType() != type || !payment.getPaymentId().equals(transaction.paymentId())
                    || transaction.amount() == null || payment.getAmount().compareTo(transaction.amount()) != 0
                    || !payment.getCurrency().equals(transaction.currency())
                    || transaction.id().equals(authorizationId)) {
                throw new PaymentApiException("The payment operation returned an invalid " + type + " transaction");
            }
            return transaction;
        } catch (PaymentApiException | RuntimeException exception) {
            // A thrown operation has no trustworthy outcome. Preserve uncertainty without masking its cause.
            try {
                saveProgress(payment, payment.getPaymentStage(), PaymentState.UNKNOWN);
            } catch (PaymentApiException persistenceFailure) {
                exception.addSuppressed(persistenceFailure);
            }
            throw exception;
        }
    }

    private void saveProgress(Payment payment, PaymentStage stage, PaymentState state) throws PaymentApiException {
        payment.setPaymentStage(stage);
        payment.setPaymentState(state);
        try {
            paymentRepository.update(payment);
        } catch (RuntimeException exception) {
            throw new PaymentApiException("Unable to save progress for payment " + payment.getPaymentId(), exception);
        }
    }

    private static void validateRequest(Account account, UUID paymentMethodId, BigDecimal amount,
                                        Currency currency, CallContext callContext) throws PaymentApiException {
        if (account == null || account.getId() == null) {
            throw new PaymentApiException("An account ID is required");
        }
        if (paymentMethodId == null) {
            throw new PaymentApiException("A payment method ID is required");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new PaymentApiException("The payment amount must be greater than zero");
        }
        if (currency == null || callContext == null) {
            throw new PaymentApiException("Currency and call context are required");
        }
    }

    @FunctionalInterface
    private interface PaymentOperation {
        PaymentTransaction run() throws PaymentApiException;
    }
}
