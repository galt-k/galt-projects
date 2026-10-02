package org.example;

@FunctionalInterface
public interface LeavingStateCallback {
    void leavingState(State oldState) throws OperationException;
}
