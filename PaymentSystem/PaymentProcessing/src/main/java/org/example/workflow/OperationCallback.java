package org.example.workflow;

import org.example.OperationException;
import org.example.State;

@FunctionalInterface
public interface OperationCallback {
    OperationResult doOperation(State currentState) throws OperationException;
}
