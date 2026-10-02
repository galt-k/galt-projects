package org.example;

import org.example.statemachine.PaymentStateContext;
import org.example.states.Authorized;
import org.example.states.CapturePending;
import org.example.states.Succeeded;

public final class CaptureStateMachineHelper extends OperationStateMachineHelper {
    public CaptureStateMachineHelper(PaymentStateContext context, String pluginName) {
        super(context, pluginName, TransactionType.CAPTURE,
                Authorized.INSTANCE, CapturePending.INSTANCE, Succeeded.INSTANCE);
    }
}
