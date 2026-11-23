import java.lang.management.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.*;

/** Teaching simulator: no model, GPU, or actual KV-cache allocation. */
public class DeadlockLab {
    record Attempt(long id, int blocks) {}
    @FunctionalInterface interface Step { void run() throws InterruptedException; }

    static final class MemoryBudget {
        private final ReentrantLock lock = new ReentrantLock();
        private final Map<Long, Integer> reservations = new HashMap<>();
        private final int capacity = 1024;
        private int used;

        void reserve(Attempt attempt) throws InterruptedException {
            lock.lockInterruptibly();
            try {
                if (reservations.containsKey(attempt.id())) throw new IllegalStateException("Duplicate attempt");
                if (used + attempt.blocks() > capacity) throw new IllegalStateException("Memory exhausted");
                reservations.put(attempt.id(), attempt.blocks());
                used += attempt.blocks();
            } finally { lock.unlock(); }
        }

        void finish(Attempt attempt, Step checkpoint, Step retire) throws InterruptedException {
            lock.lockInterruptibly();
            try {
                if (!reservations.containsKey(attempt.id())) return;
                checkpoint.run();
                retire.run();
                used -= reservations.remove(attempt.id());
            } finally { lock.unlock(); }
        }
    }

    static final class Scheduler {
        private final ReentrantLock lock = new ReentrantLock();
        private final Map<Long, Attempt> active = new HashMap<>();
        private final MemoryBudget memory;
        private final AtomicInteger dispatches = new AtomicInteger();
        private final AtomicInteger completions = new AtomicInteger();

        Scheduler(MemoryBudget memory) { this.memory = memory; }

        void dispatch(Attempt attempt, Step checkpoint) throws InterruptedException {
            lock.lockInterruptibly();
            try {
                if (active.size() >= 2) throw new IllegalStateException("No execution slot");
                if (active.containsKey(attempt.id())) throw new IllegalStateException("Duplicate attempt");
                checkpoint.run();
                memory.reserve(attempt);
                active.put(attempt.id(), attempt);
                dispatches.incrementAndGet();
            } finally { lock.unlock(); }
        }

        void completed(Attempt attempt, Step checkpoint) throws InterruptedException {
            lock.lockInterruptibly();
            try {
                memory.finish(attempt, checkpoint, () -> retire(attempt));
            } finally {
                lock.unlock();
            }
        }

        private void retire(Attempt attempt) throws InterruptedException {
            lock.lockInterruptibly();
            try {
                if (active.remove(attempt.id()) != null) completions.incrementAndGet();
            } finally { lock.unlock(); }
        }

        // Harness-only audit, after both workers have exited.
        void audit(long expectedId) throws InterruptedException {
            lock.lockInterruptibly();
            try {
                memory.lock.lockInterruptibly();
                try {
                    if (!active.keySet().equals(Set.of(expectedId)) ||
                        !active.keySet().equals(memory.reservations.keySet()) || memory.used != 256)
                        throw new AssertionError("Ownership/accounting mismatch");
                } finally { memory.lock.unlock(); }
            } finally { lock.unlock(); }
        }
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length == 0 ? "incident" : args[0];
        if (!Set.of("incident", "serial").contains(mode)) throw new IllegalArgumentException("incident or serial");
        Path output = Path.of(args.length > 1 ? args[1] : "run-" + System.nanoTime());
        Files.createDirectories(output);
        if (Files.exists(output.resolve("summary.txt"))) throw new IllegalArgumentException("Choose a new directory");
        var scheduler = new Scheduler(new MemoryBudget());
        var a = new Attempt(101, 256);
        var b = new Attempt(102, 256);
        Step noCheckpoint = () -> {};
        scheduler.dispatch(a, noCheckpoint);
        var failures = new ConcurrentLinkedQueue<Throwable>();
        var arrived = new CountDownLatch(2);
        Step checkpoint = () -> {
            arrived.countDown();
            // Bounded test-only rendezvous; expires even if a candidate fix serializes operations.
            arrived.await(500, TimeUnit.MILLISECONDS);
        };
        Thread dispatch = new Thread(() -> {
            try { scheduler.dispatch(b, checkpoint); }
            catch (InterruptedException expected) { Thread.currentThread().interrupt(); }
            catch (Throwable t) { failures.add(t); }
        }, "batch-dispatch");
        Thread completion = new Thread(() -> {
            try { scheduler.completed(a, checkpoint); }
            catch (InterruptedException expected) { Thread.currentThread().interrupt(); }
            catch (Throwable t) { failures.add(t); }
        }, "batch-completion");
        // Last-resort process safety if a future exercise edit makes cleanup uninterruptible.
        dispatch.setDaemon(true);
        completion.setDaemon(true);
        System.out.println("PID=" + ProcessHandle.current().pid() + " output=" + output);
        var bean = ManagementFactory.getThreadMXBean();
        long[] cycle = null;
        int observations = 0;
        var samples = new StringBuilder("elapsed_ms,dispatches,completions,dispatch_state,completion_state\n");
        long start = System.nanoTime();
        if (mode.equals("serial")) {
            scheduler.completed(a, noCheckpoint);
            scheduler.dispatch(b, noCheckpoint);
        } else {
            dispatch.start();
            completion.start();
            while (System.nanoTime() - start < TimeUnit.SECONDS.toNanos(6)) {
                samples.append((System.nanoTime()-start)/1_000_000).append(',')
                    .append(scheduler.dispatches.get()).append(',').append(scheduler.completions.get()).append(',')
                    .append(dispatch.getState()).append(',').append(completion.getState()).append('\n');
                cycle = bean.findDeadlockedThreads();
                if (cycle != null && ++observations >= 3) break;
                if (!dispatch.isAlive() && !completion.isAlive()) break;
                Thread.sleep(100);
            }
        }
        var dump = new StringBuilder();
        for (ThreadInfo info : bean.dumpAllThreads(true, true)) dump.append(info).append('\n');
        Files.writeString(output.resolve("threads.txt"), dump);
        Files.writeString(output.resolve("metrics.csv"), samples);
        boolean stalled = dispatch.isAlive() || completion.isAlive();
        // Capture evidence first, then unwind interruptible waits; never use Thread.stop().
        dispatch.interrupt(); completion.interrupt();
        dispatch.join(2000); completion.join(2000);
        if (dispatch.isAlive() || completion.isAlive()) throw new AssertionError("Workers failed to stop");
        if (!failures.isEmpty()) throw new AssertionError(failures.peek());
        scheduler.audit(stalled ? a.id() : b.id());
        String summary = "mode=" + mode + "\ndispatches=" + scheduler.dispatches.get()
            + "\ncompletions=" + scheduler.completions.get()
            + "\nprogress=" + (stalled ? "STALLED" : "COMPLETE")
            + "\njvm_detected_cycle=" + (cycle != null)
            + "\nworker_cleanup=PASS\naccounting_check=PASS\n";
        Files.writeString(output.resolve("summary.txt"), summary);
        System.out.print(summary);
    }
}
