import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.*;

/** Local teaching simulator; not a GPU benchmark. */
public class IncidentLab {
    record Batch(long id, long ready) {}
    final ReentrantLock lock = new ReentrantLock();
    final Condition changed = lock.newCondition();
    final ArrayDeque<Batch> ready = new ArrayDeque<>();
    final ScheduledExecutorService backend = Executors.newScheduledThreadPool(2);
    final List<String> events = new ArrayList<>();
    final List<Long> waits = new ArrayList<>();
    final Set<Long> finished = new HashSet<>();
    final long origin = System.nanoTime();
    final boolean incident;
    final long serviceMs;
    int occupied, accepted, rejected, started, completed, maxOccupied;
    boolean closed;
    long lastAccounting = origin, availableWhileReady, occupiedNanos;
    volatile Throwable failure;

    IncidentLab(boolean incident, long serviceMs) {
        this.incident = incident;
        this.serviceMs = serviceMs;
    }
    long now() { return System.nanoTime(); }
    // All state and observations use the same lock.
    void account() {
        long t = now(), elapsed = t - lastAccounting;
        if (!ready.isEmpty() && occupied < 2) availableWhileReady += elapsed;
        occupiedNanos += elapsed * occupied;
        lastAccounting = t;
    }
    void event(String type, long id) {
        events.add(String.format(Locale.ROOT, "%.3f,%s,%d,%d,%d",
                (now() - origin) / 1e6, type, id, occupied, ready.size()));
    }
    void submit(long id) {
        lock.lock();
        try {
            account();
            if (ready.size() == 1024) { rejected++; event("REJECTED", id); return; }
            ready.addLast(new Batch(id, now()));
            accepted++;
            event("READY", id);
            changed.signalAll();
            event("SIGNAL", id);
        } finally { lock.unlock(); }
    }
    void complete(Batch b) {
        lock.lock();
        try {
            account();
            if (!finished.add(b.id())) throw new AssertionError("Duplicate completion");
            occupied--;
            completed++;
            event("COMPLETE_RELEASE", b.id());
            // Capacity changed: wake the scheduler while holding the state lock.
            changed.signalAll();
            event("SIGNAL", b.id());
        } catch (Throwable t) { failure = t; changed.signalAll(); }
        finally { lock.unlock(); }
    }
    void schedule() {
        try {
            while (true) {
                long attempt = now();
                lock.lockInterruptibly();
                try {
                    event("LOCK_ACQUIRED", -1);
                    events.add(String.format(Locale.ROOT, "%.3f,LOCK_WAIT_US_%d,-1,%d,%d",
                            (now()-origin)/1e6, (now()-attempt)/1000, occupied, ready.size()));
                    while (ready.isEmpty() || occupied == 2) {
                        if (failure != null) return;
                        if (closed && ready.isEmpty() && occupied == 0) return;
                        event("WAIT_ENTER", -1);
                        changed.awaitNanos(TimeUnit.MILLISECONDS.toNanos(250));
                        event("WAIT_RETURN", -1);
                    }
                    account();
                    Batch b = ready.removeFirst();
                    occupied++;
                    maxOccupied = Math.max(maxOccupied, occupied);
                    if (occupied > 2) throw new AssertionError("Capacity exceeded");
                    started++;
                    waits.add(now() - b.ready());
                    event("START", b.id());
                    backend.schedule(() -> complete(b), serviceMs, TimeUnit.MILLISECONDS);
                } finally { lock.unlock(); }
            }
        } catch (Throwable t) { failure = t; }
    }
    static double percentile(List<Long> values, double p) {
        if (values.isEmpty()) return 0;
        var sorted = new ArrayList<>(values);
        sorted.sort(Long::compare);
        return sorted.get((int)Math.ceil(p * sorted.size()) - 1) / 1e6;
    }
    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "incident";
        if (!Set.of("baseline", "incident").contains(mode)) throw new IllegalArgumentException("baseline or incident");
        Path output = Path.of(args.length > 1 ? args[1] : "run-" + System.currentTimeMillis());
        int seconds = args.length > 2 ? Integer.parseInt(args[2]) : 12;
        long service = args.length > 3 ? Long.parseLong(args[3]) : 40;
        if (seconds < 1 || seconds > 120 || service < 1 || service > 1000)
            throw new IllegalArgumentException("seconds: 1..120; service milliseconds: 1..1000");
        Files.createDirectories(output);
        if (Files.exists(output.resolve("events.csv"))) throw new IOException("Use a new output directory");
        var lab = new IncidentLab(mode.equals("incident"), service);
        Thread scheduler = new Thread(lab::schedule, "inference-scheduler");
        scheduler.start();
        System.out.println("PID=" + ProcessHandle.current().pid() + " mode=" + mode + " output=" + output);
        long id = 0;
        // Open-loop bursts: eight ready batches every 250 ms (32 batches/sec).
        for (int tick = 0; tick < seconds * 4; tick++) {
            long deadline = lab.origin + TimeUnit.MILLISECONDS.toNanos(tick * 250L);
            long remaining;
            while ((remaining = deadline - lab.now()) > 0) TimeUnit.NANOSECONDS.sleep(remaining);
            for (int j = 0; j < 8; j++) lab.submit(++id);
        }
        long end = lab.origin + TimeUnit.SECONDS.toNanos(seconds);
        if (lab.now() < end) TimeUnit.NANOSECONDS.sleep(end - lab.now());
        String summary;
        lab.lock.lock();
        try {
            lab.account();
            double elapsed = (lab.now() - lab.origin) / 1e9;
            summary = String.format(Locale.ROOT,
                "mode=%s%nmeasurement_seconds=%.3f%naccepted=%d%nrejected=%d%ncompleted_in_window=%d%ncompletion_rate=%.2f batches/s%nready_at_window_end=%d%nready_to_start_p50_ms=%.3f%nready_to_start_p95_ms=%.3f%nready_with_free_slot_ms=%.3f%nslot_occupancy_percent=%.2f%n",
                mode, elapsed, lab.accepted, lab.rejected, lab.completed, lab.completed/elapsed,
                lab.ready.size(), percentile(lab.waits,.5), percentile(lab.waits,.95),
                lab.availableWhileReady/1e6, lab.occupiedNanos/(elapsed*1e9*2)*100);
            lab.event("MEASUREMENT_END", -1);
            lab.closed = true;
            lab.changed.signalAll();
        } finally { lab.lock.unlock(); }
        scheduler.join(60000);
        if (scheduler.isAlive()) { scheduler.interrupt(); lab.backend.shutdownNow(); throw new AssertionError("Drain timeout"); }
        lab.backend.shutdown();
        lab.backend.awaitTermination(5, TimeUnit.SECONDS);
        if (lab.failure != null) throw new AssertionError(lab.failure);
        if (lab.completed != lab.accepted || lab.occupied != 0 || !lab.ready.isEmpty())
            throw new AssertionError("Lost work or leaked capacity");
        Files.writeString(output.resolve("summary.txt"), summary + "drain_check=PASS\n");
        Files.write(output.resolve("events.csv"),
                java.util.stream.Stream.concat(java.util.stream.Stream.of("elapsed_ms,event,batch_id,occupied_slots,ready_batches"), lab.events.stream()).toList());
        System.out.print(summary);
        System.out.println("drain_check=PASS (all accepted batches completed once; no slots leaked)");
    }
}
