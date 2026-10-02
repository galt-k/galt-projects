package org.example.exceptions;

import org.example.PaymentEvent;
import org.example.State;

public class InvalidPaymentTransitionException extends RuntimeException{
    public InvalidPaymentTransitionException(State state, PaymentEvent event){
        super("Invalid transition " + state.name() + " with event " + event);
    }
}
