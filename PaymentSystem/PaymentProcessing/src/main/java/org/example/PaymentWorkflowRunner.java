package org.example;

import org.example.persistence.PaymentWorkflowDAOHelper;

import org.example.statemachine.PaymentStateContext;

import org.example.callbacks.AuthorizeInitiated;
import org.example.callbacks.CaptureInitiated;
import org.example.callbacks.CheckoutInitiated;
import org.example.workflow.OperationCallback;
import org.example.workflow.EnteringStateCallback;
import org.example.callbacks.CaptureCompleted;
import org.example.callbacks.AuthorizeCompleted;
import org.example.callbacks.CheckoutCompleted;
import org.example.callbacks.AuthorizeOperation;
import org.example.callbacks.CaptureOperation;
import org.example.callbacks.CheckoutOperation;
import org.example.plugin.PaymentPluginAPI;
import org.example.ledger.LedgerClient;

import java.util.Objects;
import java.util.UUID;

/** Resolves the configured operation and delegates lifecycle execution to its initial state. */
public class PaymentWorkflowRunner {
    private final PaymentStateMachineHelper paymentStateMachineHelper;
    private final PaymentPluginAPI paymentPluginApi;
    private final LedgerClient ledgerClient;

    public PaymentWorkflowRunner(PaymentStateMachineHelper paymentStateMachineHelper,
                                 PaymentPluginAPI paymentPluginApi) {
        this(paymentStateMachineHelper, paymentPluginApi, null);
    }

    public PaymentWorkflowRunner(PaymentStateMachineHelper paymentStateMachineHelper,
                                 PaymentPluginAPI paymentPluginApi, LedgerClient ledgerClient) {
        this.paymentStateMachineHelper = Objects.requireNonNull(paymentStateMachineHelper);
        this.paymentPluginApi = Objects.requireNonNull(paymentPluginApi);
        this.ledgerClient = ledgerClient;
    }

    public void run(PaymentStateContext paymentStateContext,
                    PaymentWorkflowDAOHelper daoHelper,
                    String currentStateNameOrNull,
                    TransactionType transactionType) throws PaymentAPIException {
        Objects.requireNonNull(paymentStateContext, "paymentStateContext");
        Objects.requireNonNull(daoHelper, "daoHelper");
        Objects.requireNonNull(transactionType, "transactionType");

        String currentStateName = Objects.requireNonNullElse(
                currentStateNameOrNull,
                paymentStateMachineHelper.getInitStateNameForTransaction(transactionType));

        OperationCallback operationCallback;
        LeavingStateCallback leavingStateCallback;
        EnteringStateCallback enteringStateCallback;
        boolean includeDeletedPaymentMethod = false;

        switch (transactionType) {
            case CHECKOUT -> {
                operationCallback = new CheckoutOperation(daoHelper, paymentStateContext, paymentPluginApi);
                leavingStateCallback = new CheckoutInitiated(daoHelper, paymentStateContext);
                enteringStateCallback = new CheckoutCompleted(daoHelper, paymentStateContext);
            }
            case AUTHORIZE -> {
                operationCallback = new AuthorizeOperation(daoHelper, paymentStateContext, paymentPluginApi);
                leavingStateCallback = new AuthorizeInitiated(daoHelper, paymentStateContext);
                enteringStateCallback = new AuthorizeCompleted(daoHelper, paymentStateContext);
            }
            case CAPTURE -> {
                if (ledgerClient == null) {
                    throw new PaymentAPIException("Capture requires a ledger client", null);
                }
                operationCallback = new CaptureOperation(daoHelper, paymentStateContext, paymentPluginApi, ledgerClient);
                leavingStateCallback = new CaptureInitiated(daoHelper, paymentStateContext);
                enteringStateCallback = new CaptureCompleted(daoHelper, paymentStateContext);
            }
            default -> throw new IllegalStateException("Unsupported transaction type: " + transactionType);
        }

        runStateMachineOperation(currentStateName, transactionType,
                leavingStateCallback, operationCallback, enteringStateCallback,
                includeDeletedPaymentMethod, paymentStateContext, daoHelper);
    }

    public PaymentResult runStateMachineOperation(
            String initialStateName,
            TransactionType transactionType,
            LeavingStateCallback leavingStateCallback,
            OperationCallback operationCallback,
            EnteringStateCallback enteringStateCallback,
            Boolean includeDeletedPaymentMethod,
            PaymentStateContext paymentStateContext,
            PaymentWorkflowDAOHelper daoHelper) throws PaymentAPIException {
        try {
            // 1. Resolve the provider, including deleted payment methods when requested.
            String pluginName = daoHelper.getPaymentProviderPluginName(
                    paymentStateContext.getAccountId(), paymentStateContext.getPaymentMethodId(), includeDeletedPaymentMethod);

            // 2. Select the provider's configuration using the internal call context.
            StateMachineConfig stateMachineConfig = paymentStateMachineHelper.getStateMachineConfig(
                    pluginName, paymentStateContext.getInternalCallContext());

            // 3. Find the machine that owns the named state, then load that state.
            StateMachine initialStateMachine = stateMachineConfig.getStateMachineForState(initialStateName);
            WorkflowState initialState = initialStateMachine.getState(initialStateName);

            // 4. Resolve the transaction's configured operation.
            Operation operation = paymentStateMachineHelper.getOperationForTransaction(
                    stateMachineConfig, transactionType);

            // 5. The state owns lifecycle ordering; the runner only supplies the callbacks.
            return initialState.runOperation(
                    operation, operationCallback, enteringStateCallback, leavingStateCallback);
        } catch (MissingEntryException exception) {
            // Preserve the missing configuration entry as the cause for diagnosis.
            throw new PaymentAPIException(
                    "Missing state-machine entry for initial state '" + initialStateName
                            + "' and transaction " + transactionType, exception);
        } catch (OperationException exception) {
            throw new PaymentAPIException(
                    "State-machine operation failed for initial state '" + initialStateName
                            + "' and transaction " + transactionType, exception);
        }
    }
}

// State-machine contracts shared by configured lifecycle executors.
interface PaymentStateMachineHelper {
    String getInitStateNameForTransaction(TransactionType transactionType) throws PaymentAPIException;

    StateMachineConfig getStateMachineConfig(String pluginName, InternalCallContext internalCallContext)
            throws MissingEntryException, PaymentAPIException;

    Operation getOperationForTransaction(StateMachineConfig stateMachineConfig, TransactionType transactionType)
            throws MissingEntryException;
}

interface StateMachineConfig {
    StateMachine getStateMachineForState(String initialStateName) throws MissingEntryException;
}

interface StateMachine {
    WorkflowState getState(String initialStateName) throws MissingEntryException;
}

// Lifecycle execution is separate from the State objects supplied to callbacks.
interface WorkflowState {
    PaymentResult runOperation(Operation operation,
                               OperationCallback operationCallback,
                               EnteringStateCallback enteringStateCallback,
                               LeavingStateCallback leavingStateCallback)
            throws MissingEntryException, PaymentAPIException, OperationException;
}

record Operation(String name) {}

// Retain the existing result contract while the state-machine contracts are filled in.
record OperationType(String name) {}

enum OperationState { SUCCESS, FAILURE, UNKNOWN }

record PaymentResult(UUID paymentId, OperationType operationType, OperationState state) {}

class MissingEntryException extends Exception {
    MissingEntryException(String message) {
        super(message);
    }
}
