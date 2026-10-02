package org.example;
import org.springframework.stereotype.Component;

/*
1. Check if amount is negative
 */
@Component
public class PaymentValidator {
    // this method is thread safe and can be executed by multithreads.
    public boolean isAmountValid(PaymentItem paymentItem) {
        if(paymentItem.amount() <= 0){
            return false;
        }
        return true;
    }
}
