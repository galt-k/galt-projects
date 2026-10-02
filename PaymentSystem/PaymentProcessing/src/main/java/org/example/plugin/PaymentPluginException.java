package org.example.plugin;

public class PaymentPluginException extends Exception {
    private static final long serialVersionUID = 1L;

    public PaymentPluginException(String message) {
        super(message);
    }

    public PaymentPluginException(String message, Throwable cause) {
        super(message, cause);
    }
}
