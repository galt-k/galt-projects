package org.example.plugin;

public class PaymentPluginAPIException extends PaymentPluginException {
    private static final long serialVersionUID = 1L;

    public PaymentPluginAPIException(String message) {
        super(message);
    }

    public PaymentPluginAPIException(String message, Throwable cause) {
        super(message, cause);
    }
}
