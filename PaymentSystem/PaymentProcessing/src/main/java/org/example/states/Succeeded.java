package org.example.states;

public final class Succeeded extends AbstractPaymentState {
    public static final Succeeded INSTANCE = new Succeeded();
    private Succeeded() {}

    @Override public String name() { return "SUCCEEDED"; }
    // Terminal – advance & recover stay as default (illegal / no-op)
}
