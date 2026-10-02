package org.example.states;

public final class Failed extends AbstractPaymentState {
    public static final Failed INSTANCE = new Failed();
    private Failed() {}

    @Override public String name() { return "FAILED"; }
}