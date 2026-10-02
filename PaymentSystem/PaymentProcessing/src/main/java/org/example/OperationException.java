package org.example;

public class OperationException extends Exception {
    private static final long serialVersionUID = 1L;

    public OperationException(String message) {
        super(message);
    }

    public OperationException(String message, Throwable cause) {
        super(message, cause);
    }
}
