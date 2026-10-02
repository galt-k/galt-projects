package org.killbill.billing.payment.core;

import org.example.CaptureStateMachineHelper;
import org.example.Payment;
import org.example.PaymentAPIException;
import org.example.PaymentStage;
import org.example.PaymentState;
import org.example.PaymentWorkflowRunner;
import org.example.TransactionStatus;
import org.example.TransactionType;
import org.example.ledger.LedgerClient;
import org.example.persistence.PaymentRepository;
import org.example.persistence.PaymentTransactionModel;
import org.example.persistence.PaymentTransactionRepository;
import org.example.persistence.PaymentWorkflowDAOHelper;
import org.example.plugin.PaymentPluginAPI;
import org.example.statemachine.PaymentStateContext;

import java.time.Clock;
import java.util.List;
import java.util.Objects;

public final class CaptureAutomatonRunner implements PaymentAutomatonRunner {
    private final PaymentRepository payments;
    private final PaymentTransactionRepository transactions;
    private final PaymentWorkflowDAOHelper daoHelper;
    private final PaymentPluginAPI plugin;
    private final LedgerClient ledger;
    private final String pluginName;

    public CaptureAutomatonRunner(PaymentRepository payments, PaymentTransactionRepository transactions,
                                   PaymentPluginAPI plugin, LedgerClient ledger, String pluginName) {
        this.payments = Objects.requireNonNull(payments, "payments");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.pluginName = Objects.requireNonNull(pluginName, "pluginName");
        this.daoHelper = new PaymentWorkflowDAOHelper(transactions, pluginName, Clock.systemUTC());
    }

    @Override
    public void run(PaymentStateContext context) throws PaymentAPIException {
        Objects.requireNonNull(context, "context");
        if (context.getTransactionType() != TransactionType.CAPTURE || context.getPaymentId() == null
                || context.getPaymentTransaction() != null) {
            throw new PaymentAPIException("Capture requires a parent payment ID and a fresh CAPTURE context", null);
        }
        Payment parent;
        List<PaymentTransactionModel> attempts;
        try {
            parent = payments.findById(context.getPaymentId()).orElse(null);
            attempts = transactions.findTransactionsByPaymentId(context.getPaymentId());
        } catch (RuntimeException exception) {
            throw new PaymentAPIException("Unable to load capture parent payment and authorization", exception);
        }
        if (parent == null || !parent.getAccountId().equals(context.getAccountId())
                || context.getPaymentMethodId() != null && !parent.getPaymentMethodId().equals(context.getPaymentMethodId())
                || parent.getAmount().compareTo(context.getAmount()) != 0
                || !parent.getCurrency().equals(context.getCurrency())) {
            throw new PaymentAPIException("Capture request does not match its saved parent payment", null);
        }
        if (parent.getPaymentState() != PaymentState.PENDING || parent.getPaymentStage() == PaymentStage.CREATED) {
            throw new PaymentAPIException("Parent payment is not eligible for capture", null);
        }
        if (attempts.stream().anyMatch(transaction -> transaction.transactionType() == TransactionType.CAPTURE)) {
            throw new PaymentAPIException("A capture attempt already exists; resolve it before another capture", null);
        }
        boolean authorized = attempts.stream().anyMatch(transaction -> transaction.transactionType() == TransactionType.AUTHORIZE
                && transaction.status() == TransactionStatus.SUCCESS
                && transaction.accountId().equals(context.getAccountId())
                && transaction.currency().equals(context.getCurrency())
                && transaction.amount().compareTo(context.getAmount()) == 0);
        if (!authorized) {
            throw new PaymentAPIException("Capture requires a successful saved authorization for the requested amount", null);
        }
        context.setPaymentMethodId(parent.getPaymentMethodId());
        new PaymentWorkflowRunner(new CaptureStateMachineHelper(context, pluginName), plugin, ledger)
                .run(context, daoHelper, null, TransactionType.CAPTURE);
    }
}
