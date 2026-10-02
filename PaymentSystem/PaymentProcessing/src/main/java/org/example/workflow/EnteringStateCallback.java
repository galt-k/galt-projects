package org.example.workflow;

import org.example.LeavingStateCallback;
import org.example.OperationException;
import org.example.State;

@FunctionalInterface
public interface EnteringStateCallback {
    void enteringState(State newState,
                       OperationCallback operationCallback,
                       OperationResult operationResult,
                       LeavingStateCallback leavingStateCallback) throws OperationException;
}
