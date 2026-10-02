package org.killbill.billing.payment.core;

import org.example.PaymentAPIException;
import org.example.ledger.LedgerClient;
import org.example.persistence.PaymentRepository;
import org.example.persistence.PaymentTransactionRepository;
import org.example.plugin.PaymentPluginAPI;
import org.example.statemachine.PaymentStateContext;

public final class DefaultPaymentAutomatonRunner implements PaymentAutomatonRunner {
    private final AuthorizationAutomatonRunner authorization;
    private final CaptureAutomatonRunner capture;

    public DefaultPaymentAutomatonRunner(PaymentRepository payments, PaymentTransactionRepository transactions,
                                         PaymentPluginAPI plugin, LedgerClient ledger, String pluginName) {
        authorization = new AuthorizationAutomatonRunner(payments, transactions, plugin, pluginName);
        capture = new CaptureAutomatonRunner(payments, transactions, plugin, ledger, pluginName);
    }

    @Override
    public void run(PaymentStateContext context) throws PaymentAPIException {
        switch (context.getTransactionType()) {
            case AUTHORIZE -> authorization.run(context);
            case CAPTURE -> capture.run(context);
            case PURCHASE, CHECKOUT -> throw new PaymentAPIException("Checkout must sequence authorization and capture", null);
        }
    }
}
