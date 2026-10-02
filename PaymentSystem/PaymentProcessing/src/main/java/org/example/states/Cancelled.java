package org.example.states;

public final class Cancelled extends AbstractPaymentState{
    public static final Cancelled INSTANCE = new Cancelled();
    private Cancelled() {}

    @Override
    public String name() {
        return "Cancelled";
    }
}
