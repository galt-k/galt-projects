package org.killbill.billing.payment.checkout;

import org.example.Payment;
import org.example.PaymentStage;
import org.example.PaymentState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CheckoutRequestIdentityTest {
    private final UUID account = UUID.randomUUID();
    private final UUID method = UUID.randomUUID();
    private final Currency usd = Currency.getInstance("USD");

    @ParameterizedTest
    @CsvSource({"10.0,10.00", "1000,1E+3", "12.3400,12.34"})
    void equivalentAmountsHaveTheSameIdentity(String first, String second) {
        var original = new CheckoutRequestIdentity(account, method, new BigDecimal(first), usd);
        var retry = new CheckoutRequestIdentity(account, method, new BigDecimal(second), usd);
        assertEquals(original, retry);
        assertEquals(original.hashCode(), retry.hashCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"account", "method", "amount", "currency"})
    void changingAnyBusinessFieldChangesIdentity(String changed) {
        var original = new CheckoutRequestIdentity(account, method, new BigDecimal("10.00"), usd);
        var retry = new CheckoutRequestIdentity(changed.equals("account") ? UUID.randomUUID() : account,
                changed.equals("method") ? UUID.randomUUID() : method,
                new BigDecimal(changed.equals("amount") ? "10.000000000000000001" : "10.00"),
                changed.equals("currency") ? Currency.getInstance("EUR") : usd);
        assertNotEquals(original, retry);
    }

    @Test
    void paymentProgressDoesNotChangeRequestIdentityOrOriginalAmount() {
        var payment = new Payment(UUID.randomUUID(), account, method, new BigDecimal("10.00"), usd);
        var original = CheckoutRequestIdentity.from(payment);
        payment.setPaymentStage(PaymentStage.CAPTURE);
        payment.setPaymentState(PaymentState.SUCCEEDED);
        assertEquals(original, CheckoutRequestIdentity.from(payment));
        assertEquals(new BigDecimal("10.00"), payment.getAmount());
    }
}
