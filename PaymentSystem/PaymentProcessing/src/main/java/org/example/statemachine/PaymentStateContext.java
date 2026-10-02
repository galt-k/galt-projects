package org.example.statemachine;

import org.example.CallContext;
import org.example.InternalCallContext;
import org.example.persistence.PaymentTransactionModel;
import org.example.TransactionType;
import org.example.plugin.PaymentTransactionInfoPlugin;
import org.example.plugin.PluginProperty;
import org.example.plugin.PaymentPluginStatus;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Mutable request and intermediate data for one payment workflow execution.
 */
public class PaymentStateContext {
    private UUID accountId;
    private UUID paymentId;
    private UUID paymentMethodId;
    private BigDecimal amount;
    private Currency currency;
    private TransactionType transactionType;
    private PaymentTransactionModel paymentTransaction;
    private PaymentTransactionInfoPlugin paymentInfoPlugin;
    private Exception overridePluginException;
    private PaymentPluginStatus gatewayStatus;
    private CallContext callContext;
    private InternalCallContext internalCallContext;
    private Iterable<PluginProperty> properties = List.of();

    public PaymentStateContext(UUID accountId,
                               UUID paymentMethodId,
                               BigDecimal amount,
                               Currency currency,
                               TransactionType transactionType,
                               CallContext callContext) {
        this.accountId = Objects.requireNonNull(accountId, "accountId");
        this.paymentMethodId = transactionType == TransactionType.CAPTURE
                ? paymentMethodId : Objects.requireNonNull(paymentMethodId, "paymentMethodId");
        this.amount = Objects.requireNonNull(amount, "amount");
        this.currency = Objects.requireNonNull(currency, "currency");
        this.transactionType = Objects.requireNonNull(transactionType, "transactionType");
        this.callContext = Objects.requireNonNull(callContext, "callContext");
    }

    public UUID getAccountId() {
        return accountId;
    }

    public void setAccountId(UUID accountId) {
        this.accountId = Objects.requireNonNull(accountId, "accountId");
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public void setPaymentId(UUID paymentId) {
        this.paymentId = paymentId;
    }

    public UUID getPaymentMethodId() {
        return paymentMethodId;
    }

    public void setPaymentMethodId(UUID paymentMethodId) {
        this.paymentMethodId = Objects.requireNonNull(paymentMethodId, "paymentMethodId");
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = Objects.requireNonNull(amount, "amount");
    }

    public Currency getCurrency() {
        return currency;
    }

    public void setCurrency(Currency currency) {
        this.currency = Objects.requireNonNull(currency, "currency");
    }

    public TransactionType getTransactionType() {
        return transactionType;
    }

    public void setTransactionType(TransactionType transactionType) {
        this.transactionType = Objects.requireNonNull(transactionType, "transactionType");
    }

    public PaymentTransactionModel getPaymentTransaction() {
        return paymentTransaction;
    }

    public void setPaymentTransaction(PaymentTransactionModel paymentTransaction) {
        this.paymentTransaction = paymentTransaction;
    }

    public UUID getTransactionId() {
        return paymentTransaction == null ? null : paymentTransaction.id();
    }

    public UUID requireOperationIdempotencyKey(TransactionType expectedType) throws org.example.OperationException {
        if (paymentTransaction == null || paymentTransaction.id() == null
                || transactionType != expectedType || paymentTransaction.transactionType() != expectedType
                || !Objects.equals(accountId, paymentTransaction.accountId())
                || !Objects.equals(paymentId, paymentTransaction.paymentId())
                || paymentTransaction.amount() == null || amount.compareTo(paymentTransaction.amount()) != 0
                || !currency.equals(paymentTransaction.currency())) {
            throw new org.example.OperationException("Operation requires its saved transaction intent and matching request data");
        }
        return paymentTransaction.id();
    }

    public PaymentTransactionInfoPlugin getPaymentInfoPlugin() {
        return paymentInfoPlugin;
    }

    public void setPaymentInfoPlugin(PaymentTransactionInfoPlugin paymentInfoPlugin) {
        this.paymentInfoPlugin = paymentInfoPlugin;
    }

    public Exception getOverridePluginException() {
        return overridePluginException;
    }

    public void setOverridePluginException(Exception overridePluginException) {
        this.overridePluginException = overridePluginException;
    }

    public Exception getException() {
        return getOverridePluginException();
    }

    public void setException(Exception exception) {
        setOverridePluginException(exception);
    }

    public boolean hasException() {
        return overridePluginException != null;
    }

    public PaymentPluginStatus getGatewayStatus() {
        return gatewayStatus;
    }

    public void setGatewayStatus(PaymentPluginStatus gatewayStatus) {
        this.gatewayStatus = gatewayStatus;
    }

    public CallContext getCallContext() {
        return callContext;
    }

    public void setCallContext(CallContext callContext) {
        this.callContext = Objects.requireNonNull(callContext, "callContext");
    }

    public InternalCallContext getInternalCallContext() {
        return internalCallContext;
    }

    public void setInternalCallContext(InternalCallContext internalCallContext) {
        this.internalCallContext = internalCallContext;
    }

    public Iterable<PluginProperty> getProperties() {
        return properties;
    }

    public void setProperties(Iterable<PluginProperty> properties) {
        this.properties = Objects.requireNonNull(properties, "properties");
    }
}
