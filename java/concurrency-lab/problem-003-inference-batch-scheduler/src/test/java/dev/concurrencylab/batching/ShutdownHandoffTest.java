package dev.concurrencylab.batching;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import jdk.jfr.Enabled;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shutdown/handoff review exercise. Remove @Disabled from each test as you implement it.
 *
 * Invariants:
 * 1. Shutdown and handoff use one shared lock to decide which wins.
 * 2. Shutdown removes RESERVED attempts and preserves HANDING_OFF attempts.
 * 3. A successful handoff retains ownership; it never frees a slot.
 * 4. Missing attempts are not recreated; repeated handoff returns false.
 * 5. Repeated shutdown leaves the same ownership state.
 * 6. Race outcomes must match one of the two valid serial orderings.
 * 7. Every test worker must finish; propagate worker failures and use bounded waits.
 *
 * The reflection helpers only set up/inspect fixtures. Do not use them while workers
 * are running. Production reservation APIs are not integrated yet. This keeps test
 * setup from adding a public mutation API to the scheduler.
 */
@Timeout(10)
class ShutdownHandoffTest {
    private static final long ATTEMPT_A = 101;
    private static final long ATTEMPT_B = 102;

    @Test
    //@Enabled("Exercise: implement shutdown-first ordering")
    void shutdownBeforeHandoffRejectsAndRemovesReservation() throws Exception {
        BatchScheduler scheduler = new BatchScheduler();
        seed(scheduler, ATTEMPT_A, BatchScheduler.AttemptState.RESERVED);
        // TODO: invoke shutdown before handoff.
        /*
        I need a thread that performs the shutdown- InProgrress
        I need a thread that performs the tryCommitHandoff.
         */

        scheduler.shutdown();
        // Assert: handoff false, A absent, shutdown flag true.
        // tryCommitHandoff shuld return False
        // Reservation should not be present in the Active Attempts map.
        // shutdown flag is false.
        assertFalse(scheduler.tryCommitHandoff(ATTEMPT_A));
        assertTrue(scheduler.isShutdown());
        assertFalse(attempts(scheduler).containsKey(ATTEMPT_A));
    }

    @Test
    //@Disabled("Exercise: implement handoff-first ordering")
    void handoffBeforeShutdownPreservesCommittedAttempt() throws Exception {
        BatchScheduler scheduler = new BatchScheduler();
        seed(scheduler, ATTEMPT_A, BatchScheduler.AttemptState.RESERVED);
        // TODO: commit before shutdown.
        boolean isHandOff = scheduler.tryCommitHandoff(ATTEMPT_A);
        scheduler.shutdown();

        // Assert: handoff true, A remains HANDING_OFF, shutdown flag true.
        assertTrue(isHandOff);
        assertTrue(scheduler.isShutdown());
        assertTrue(attempts(scheduler).get(ATTEMPT_A)== BatchScheduler.AttemptState.HANDING_OFF);
    }

    @Test
    //@Disabled("Exercise: implement mixed states and idempotency")
    void repeatedShutdownRemovesOnlyReservedAttempts() throws Exception {
        BatchScheduler scheduler = new BatchScheduler();
        seed(scheduler, ATTEMPT_A, BatchScheduler.AttemptState.RESERVED);
        seed(scheduler, ATTEMPT_B, BatchScheduler.AttemptState.HANDING_OFF);
        // TODO: shut down twice.
        scheduler.shutdown();
        scheduler.shutdown();
        // Assert: A absent, B preserved, exactly one owned slot, shutdown true.
        assertTrue(attempts(scheduler).get(ATTEMPT_B) == BatchScheduler.AttemptState.HANDING_OFF);
        assertTrue(scheduler.isShutdown());
        assertTrue(attempts(scheduler).size()==1);

    }

    @Test
    //@Disabled("Exercise: implement repeated commitment and missing identity checks")
    void repeatedOrMissingHandoffDoesNotCreateOwnership() throws Exception {
        BatchScheduler scheduler = new BatchScheduler();
        seed(scheduler, ATTEMPT_A, BatchScheduler.AttemptState.RESERVED);
        // TODO: commit A twice and attempt handoff for missing B.
        boolean commit1 = scheduler.tryCommitHandoff(ATTEMPT_A);
        boolean commit2 = scheduler.tryCommitHandoff(ATTEMPT_A);
        boolean commit3 = scheduler.tryCommitHandoff(ATTEMPT_B);
        // Assert: only first commit true; A HANDING_OFF; B absent; size remains one.
        assertTrue(commit1);
        assertFalse(commit2);
        assertFalse(commit3);
    }

    @Test
    //@Disabled("Exercise: coordinate a concurrent start and validate allowed outcomes")
    void concurrentShutdownAndHandoffMatchAValidSerialOutcome() throws Exception {
        // TODO: repeat with a fresh scheduler and RESERVED A per trial.
        BatchScheduler scheduler = new BatchScheduler();
        seed(scheduler, ATTEMPT_A, BatchScheduler.AttemptState.RESERVED);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {


            Future<Boolean> thread1FutureResult = executor.submit(() ->
                    {
                        //start.await();
                        ready.countDown();
                        try {
                            start.await();
                            return scheduler.tryCommitHandoff(ATTEMPT_A);
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                    }
            );
            Future<?> thread2FutureResult = executor.submit(() ->
            {
                ready.countDown();
                try {
                    start.await();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                scheduler.shutdown();
            });

            boolean bothArrived = ready.await(2, TimeUnit.SECONDS);
            assertTrue(bothArrived, "Both workers should reach the start gate");
            start.countDown();

            boolean committed = thread1FutureResult.get(2, TimeUnit.SECONDS);
            thread2FutureResult.get(2, TimeUnit.SECONDS);

            assertTrue(scheduler.isShutdown(), "Shutdown must finish in either ordering");
            if (committed) {
                assertEquals(BatchScheduler.AttemptState.HANDING_OFF,
                        attempts(scheduler).get(ATTEMPT_A));
                assertEquals(1, attempts(scheduler).size());
            } else {
                assertFalse(attempts(scheduler).containsKey(ATTEMPT_A));
                assertEquals(0, attempts(scheduler).size());
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS),
                    "Test workers must terminate");

          }



        //AtomicInteger gates = new AtomicInteger(0);
        // Start two workers, then release them through a coordinated start gate.
//        Thread thread1 = new Thread(new Runnable() {
//            @Override
//            public void run() {
//                try {
//                    //Increase the counter
//                    gates.getAndAdd(1);
//                    //I have to pause the execution
//                    wait();
//                    scheduler.tryCommitHandoff(ATTEMPT_A);
//                } catch (Exception e){
//
//                }
//            }
//        });
//
//        Thread thread2 = new Thread(new Runnable() {
//            @Override
//            public void run() {
//                //Increase the counter
//                gates.getAndAdd(1);
//                //I have to pause the execution
//                wait();
//                scheduler.shutdown();
//            }
//        });

        //Check if the gates is 2
        //if(gates.get()==2){
        //    //release botht he threads
        //    notifyAll();
        //}

        // One calls handoff; the other calls shutdown. Do not use sleep for ordering.
        // Bound completion waits, propagate exceptions, and stop/join workers in finally.
        // Read fixture state only after workers finish.
        // Valid outcome 1: result true, A HANDING_OFF, shutdown true.
        // Valid outcome 2: result false, A absent, shutdown true.
        // Reject every other result/state combination.
        // Repetition is evidence, not an exhaustive proof of race freedom.
    }

    private static void seed(BatchScheduler scheduler, long id,
                             BatchScheduler.AttemptState state) throws Exception {
        attempts(scheduler).put(id, state);
    }

    @SuppressWarnings("unchecked")
    private static Map<Long, BatchScheduler.AttemptState> attempts(
            BatchScheduler scheduler) throws Exception {
        Field field = BatchScheduler.class.getDeclaredField("activeAttempts");
        field.setAccessible(true);
        return (Map<Long, BatchScheduler.AttemptState>) field.get(scheduler);
    }

    private static boolean isShutdown(BatchScheduler scheduler) throws Exception {
        Field field = BatchScheduler.class.getDeclaredField("shutdown");
        field.setAccessible(true);
        return field.getBoolean(scheduler);
    }
}
