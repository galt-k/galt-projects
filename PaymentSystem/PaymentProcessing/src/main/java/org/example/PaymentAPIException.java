package org.example;

public class PaymentAPIException extends Exception {
    private static final long serialVersionUID = 1L;

    public PaymentAPIException(String message, Throwable cause) {
        super(message, cause);
    }
}
