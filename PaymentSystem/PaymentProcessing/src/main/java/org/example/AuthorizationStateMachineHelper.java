package org.example;

import org.example.statemachine.PaymentStateContext;
import org.example.states.AuthorizationPending;
import org.example.states.Authorized;
import org.example.states.Created;

public final class AuthorizationStateMachineHelper extends OperationStateMachineHelper {
    public AuthorizationStateMachineHelper(PaymentStateContext context, String pluginName) {
        super(context, pluginName, TransactionType.AUTHORIZE,
                Created.INSTANCE, AuthorizationPending.INSTANCE, Authorized.INSTANCE);
    }
}
