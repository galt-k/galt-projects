package org.example.persistence;

public class PaymentStorageException extends RuntimeException {
    public PaymentStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
