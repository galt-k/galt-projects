package org.example.callbacks;

import org.example.PaymentAPIException;
import org.example.OperationException;
import org.example.TransactionType;
import org.example.statemachine.PaymentStateContext;
import org.example.persistence.PaymentWorkflowDAOHelper;
import org.example.plugin.PaymentPluginAPI;
import org.example.plugin.PaymentPluginAPIException;
import org.example.plugin.PaymentPluginStatus;
import org.example.workflow.OperationCallbackBase;
import org.example.ledger.LedgerClient;
import org.example.ledger.LedgerException;
import org.example.ledger.LedgerRequest;
import org.example.ledger.LedgerResponse;
import org.example.plugin.PaymentTransactionInfoPlugin;
import java.util.Objects;

public final class CaptureOperation extends OperationCallbackBase {
    private final LedgerClient ledgerClient;
    public CaptureOperation(PaymentWorkflowDAOHelper daoHelper,
                               PaymentStateContext paymentStateContext,
                               PaymentPluginAPI paymentPluginApi, LedgerClient ledgerClient) {
        super(daoHelper, paymentStateContext, paymentPluginApi);
        this.ledgerClient = Objects.requireNonNull(ledgerClient, "ledgerClient");
    }

    @Override
    protected PaymentPluginStatus doOperationCallback(PaymentPluginAPI pluginApi, PaymentStateContext context)
            throws PaymentPluginAPIException, PaymentAPIException, OperationException {
        try {
            LedgerResponse response = ledgerClient.postEntry(new LedgerRequest(context.getAccountId(),
                    context.getPaymentId(), context.requireOperationIdempotencyKey(TransactionType.CAPTURE),
                    context.getAmount(), context.getCurrency()));
            if (response == null) {
                throw new PaymentPluginAPIException("Ledger returned no outcome");
            }
            context.setPaymentInfoPlugin(new PaymentTransactionInfoPlugin(response.entryId(), response.code(), response.message()));
            return switch (response.status()) {
                case SUCCESS -> org.example.plugin.PaymentPluginStatus.PROCESSED;
                case FAILURE -> org.example.plugin.PaymentPluginStatus.ERROR;
                case PENDING -> org.example.plugin.PaymentPluginStatus.PENDING;
                case UNKNOWN -> throw new PaymentPluginAPIException("Ledger outcome is unknown");
            };
        } catch (LedgerException exception) {
            throw new PaymentPluginAPIException("Unable to confirm ledger capture outcome", exception);
        }
    }
}
