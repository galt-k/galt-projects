package org.killbill.billing.payment.core;

import org.example.AuthorizationStateMachineHelper;
import org.example.Payment;
import org.example.PaymentAPIException;
import org.example.PaymentStage;
import org.example.PaymentState;
import org.example.PaymentWorkflowRunner;
import org.example.TransactionType;
import org.example.persistence.PaymentRepository;
import org.example.persistence.PaymentTransactionRepository;
import org.example.persistence.PaymentWorkflowDAOHelper;
import org.example.plugin.PaymentPluginAPI;
import org.example.statemachine.PaymentStateContext;

import java.time.Clock;
import java.util.Objects;

public final class AuthorizationAutomatonRunner implements PaymentAutomatonRunner {
    private final PaymentRepository payments;
    private final PaymentWorkflowDAOHelper daoHelper;
    private final PaymentPluginAPI plugin;
    private final String pluginName;

    public AuthorizationAutomatonRunner(PaymentRepository payments, PaymentTransactionRepository transactions,
                                         PaymentPluginAPI plugin, String pluginName) {
        this(payments, transactions, plugin, pluginName, Clock.systemUTC());
    }

    public AuthorizationAutomatonRunner(PaymentRepository payments, PaymentTransactionRepository transactions,
                                         PaymentPluginAPI plugin, String pluginName, Clock clock) {
        this.payments = Objects.requireNonNull(payments, "payments");
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.pluginName = Objects.requireNonNull(pluginName, "pluginName");
        this.daoHelper = new PaymentWorkflowDAOHelper(transactions, pluginName, clock);
    }

    @Override
    public void run(PaymentStateContext context) throws PaymentAPIException {
        Objects.requireNonNull(context, "context");
        if (context.getTransactionType() != TransactionType.AUTHORIZE) {
            throw new PaymentAPIException("Authorization executor does not support " + context.getTransactionType(), null);
        }
        if (context.getPaymentId() == null || context.getPaymentTransaction() != null) {
            throw new PaymentAPIException("Authorization requires a parent payment ID and a fresh operation context", null);
        }
        Payment parent;
        try {
            parent = payments.findById(context.getPaymentId()).orElse(null);
        } catch (RuntimeException exception) {
            throw new PaymentAPIException("Unable to load authorization parent payment", exception);
        }
        if (parent == null || !parent.getAccountId().equals(context.getAccountId())
                || !parent.getPaymentMethodId().equals(context.getPaymentMethodId())
                || parent.getAmount().compareTo(context.getAmount()) != 0
                || !parent.getCurrency().equals(context.getCurrency())) {
            throw new PaymentAPIException("Authorization request does not match its saved parent payment", null);
        }
        if (parent.getPaymentState() != PaymentState.PENDING
                || parent.getPaymentStage() == PaymentStage.CAPTURE) {
            throw new PaymentAPIException("Parent payment is not eligible for authorization", null);
        }
        new PaymentWorkflowRunner(new AuthorizationStateMachineHelper(context, pluginName), plugin)
                .run(context, daoHelper, null, TransactionType.AUTHORIZE);
    }
}
