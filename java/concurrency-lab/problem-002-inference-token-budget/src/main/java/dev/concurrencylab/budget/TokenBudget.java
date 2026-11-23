package dev.concurrencylab.budget;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/** Review exercise: stale release-budget calculation corrected. */
public final class TokenBudget {
    public static final int CAPACITY = 10_000;

    /** Identity has reference equality and is never reassigned to another reservation. */
    private static final class Identity {}

    private record Snapshot(int available, Map<Identity, Integer> active) {
        Snapshot {
            active = Map.copyOf(active);
        }
    }

    public static final class Reservation {
        private final TokenBudget owner;
        private final Identity identity;
        private final int units;

        private Reservation(TokenBudget owner, Identity identity, int units) {
            this.owner = owner;
            this.identity = identity;
            this.units = units;
        }

        public int units() {
            return units;
        }

        /** True for the release winner; false if this reservation was already released. */
        public boolean release() {
            return owner.release(identity);
        }
    }

    private final AtomicReference<Snapshot> state =
            new AtomicReference<>(new Snapshot(CAPACITY, Map.of()));

    public Optional<Reservation> reserve(int units) {
        if (units <= 0 || units > CAPACITY) {
            throw new IllegalArgumentException("Units must be between 1 and " + CAPACITY);
        }
        Identity identity = new Identity();
        Reservation handle = new Reservation(this, identity, units);
        while (true) {
            Snapshot observed = state.get();
            if (observed.available() < units) {
                return Optional.empty();
            }
            Map<Identity, Integer> active = new HashMap<>(observed.active());
            active.put(identity, units);
            Snapshot proposed = new Snapshot(observed.available() - units, active);
            if (state.compareAndSet(observed, proposed)) {
                return Optional.of(handle);
            }
        }
    }

    public int availableUnits() {
        return state.get().available();
    }

    private boolean release(Identity identity) {
        Snapshot observed = state.get();
        Integer units = observed.active().get(identity);
        if (units == null) {
            return false;
        }
        while (true) {
            if (!observed.active().containsKey(identity)) {
                return false;
            }
            Map<Identity, Integer> active = new HashMap<>(observed.active());
            active.remove(identity);
            int restored = observed.available() + units;
            Snapshot proposed = new Snapshot(restored, active);
            if (state.compareAndSet(observed, proposed)) {
                return true;
            }
            observed = state.get();
        }
    }
}
