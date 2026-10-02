package org.killbill.billing.payment.api;

import org.example.PaymentAPIException;

public class PaymentApiException extends PaymentAPIException {
    private static final long serialVersionUID = 1L;

    public PaymentApiException(String message) {
        super(message, null);
    }

    public PaymentApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
