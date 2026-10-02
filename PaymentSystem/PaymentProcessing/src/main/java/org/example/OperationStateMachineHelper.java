package org.example;

import org.example.statemachine.PaymentStateContext;
import org.example.states.Failed;
import org.example.workflow.EnteringStateCallback;
import org.example.workflow.OperationCallback;
import org.example.workflow.OperationResult;

import java.util.Objects;

/** Enforces durable intent, side effect, and outcome ordering for one operation. */
public abstract class OperationStateMachineHelper implements PaymentStateMachineHelper {
    private final PaymentStateContext context;
    private final String pluginName;
    private final TransactionType transactionType;
    private final State initialState;
    private final State pendingState;
    private final State successfulState;
    private final StateMachineConfig configuration;

    protected OperationStateMachineHelper(PaymentStateContext context, String pluginName, TransactionType transactionType,
                                          State initialState, State pendingState, State successfulState) {
        this.transactionType = Objects.requireNonNull(transactionType);
        this.initialState = Objects.requireNonNull(initialState);
        this.pendingState = Objects.requireNonNull(pendingState);
        this.successfulState = Objects.requireNonNull(successfulState);
        this.context = Objects.requireNonNull(context, "context");
        this.pluginName = Objects.requireNonNull(pluginName, "pluginName");
        configuration = stateName -> {
            requireInitialState(stateName);
            return name -> {
                requireInitialState(name);
                return this::execute;
            };
        };
    }

    @Override
    public String getInitStateNameForTransaction(TransactionType type) throws PaymentAPIException {
        if (type != transactionType) {
            throw new PaymentAPIException("Operation executor does not support " + type, null);
        }
        return initialState.name();
    }

    @Override
    public StateMachineConfig getStateMachineConfig(String name, InternalCallContext internalContext)
            throws MissingEntryException {
        if (!pluginName.equals(name)) {
            throw new MissingEntryException("Unknown operation provider: " + name);
        }
        return configuration;
    }

    @Override
    public Operation getOperationForTransaction(StateMachineConfig config, TransactionType type) throws MissingEntryException {
        if (config != configuration || type != transactionType) {
            throw new MissingEntryException("Operation configuration does not support " + type);
        }
        return new Operation(transactionType.name());
    }

    private PaymentResult execute(Operation operation, OperationCallback operationCallback,
                                  EnteringStateCallback enteringCallback, LeavingStateCallback leavingCallback)
            throws OperationException {
        if (!transactionType.name().equals(operation.name())) {
            throw new OperationException("Unsupported operation: " + operation.name());
        }

        // An exception here aborts execution before any network callback can run.
        leavingCallback.leavingState(initialState);
        OperationResult result;
        try {
            result = operationCallback.doOperation(pendingState);
            if (result == null) {
                throw new OperationException("Operation callback returned no outcome");
            }
        } catch (OperationException | RuntimeException exception) {
            context.setException(exception);
            context.setGatewayStatus(null);
            try {
                enteringCallback.enteringState(pendingState, operationCallback,
                        OperationResult.EXCEPTION, leavingCallback);
            } catch (OperationException | RuntimeException persistenceFailure) {
                exception.addSuppressed(persistenceFailure);
            }
            throw exception;
        }

        State newState = switch (result) {
            case SUCCESS -> successfulState;
            case FAILURE -> Failed.INSTANCE;
            case PENDING, EXCEPTION -> pendingState;
        };
        enteringCallback.enteringState(newState, operationCallback, result, leavingCallback);
        OperationState outcome = switch (context.getPaymentTransaction().status()) {
            case SUCCESS -> OperationState.SUCCESS;
            case FAILURE -> OperationState.FAILURE;
            case UNKNOWN -> OperationState.UNKNOWN;
        };
        return new PaymentResult(context.getPaymentId(), new OperationType(operation.name()), outcome);
    }

    private void requireInitialState(String name) throws MissingEntryException {
        if (!initialState.name().equals(name)) {
            throw new MissingEntryException("Invalid initial operation state: " + name);
        }
    }
}
