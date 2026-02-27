package org.example;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import  java.util.concurrent.TimeUnit;

/**
 * Simple Demo comparing the memeory usage of
 * - Platform threads (thraditional OS threads)
 * - Virtual threads (project loom - lightweight, java 21+
 * @param
 * GOAL-Show dramatic memory difference when creating many blocking threads.
 * Each thread just sleeps-> simulates long I/O wait (veyr common real world case)
 * /
 */
public class Main {
    // How many threads tasks to create
    // Start small (1_000), then try 10k, 50k, 100k, 1M
    // Platform threads usually die around 20k-80k depending on machine
    // virtual threads can often reach 1M+ on a laptop
    private static final int THREAD_COUNT = 1000;

    // How long each thread sleeps
    // Long enough so you have time to observe memory usage
    private static final long SLEEP_SEC = 60;

    public static void main(String[] args) throws InterruptedException {
        // Print the startup informtion
        System.out.println("Starting..." + THREAD_COUNT + " threads" );
        System.out.println("Each thread will sleep for "+ SLEEP_SEC + " seconds");
        System.out.println("-> Use -XX:NativememoryTracking=Detail and jcmd <pid> VM.native_memory to watch usage");
        System.out.println("-> Or just watch the process in the task manager");
        System.out.println();

        // Record the start time - used only for nice logging(not perf critical)
        long start = System.currentTimeMillis();
        // option 1: Traditional platform threads
        // reuses idle ones, but each still reserves ~1 MB stack space
        //ExecutorService executor = Executors.newCachedThreadPool();

        // Option 2
        // Each task gets its own virtual thread and (few 100 bytes and small stack chunks).
        ExecutorService executor_virtual = Executors.newVirtualThreadPerTaskExecutor();

        try (executor_virtual) {
            // Create and submit many independent tasks
            for(int i = 0; i< THREAD_COUNT; i++) {
                // Capture loop variable (effectively needed for lambda)
                final int taskId = i;

                // Submit a lambda that runs on a thread from the executor
                executor_virtual.submit(() -> {
                    // Decide how to describe this thread in logs
                    // .isvirtual() is the official way to detect virtual vs platform
                    // There is no Thread Ver, Thread is a class name and current thread is a static method.
                    String threadType = Thread.currentThread().isVirtual() ? "Virtual" : "Platform";

                    // Print when the task starts
                    System.out.printf("Task %6d started -> %s thread -> %s\n", taskId, threadType, Thread.currentThread());

                    try {
                        // the blocking operation - simulates I/O wait (network, DB, file, etc)
                        // virtual threads park very cheaply when sleeping - > Carrier thread freed
                        // Platform thread keep consuming native resources the whole time.
                        Thread.sleep(TimeUnit.SECONDS.toMillis(SLEEP_SEC));
                    } catch (InterruptedException e) {
                        // Restore interrupted status -good practise
                        Thread.currentThread().interrupt();
                    }

                    // Print when done
                    System.out.printf("Task %6d finished -> %s thread -> %s\n", taskId, threadType);
                });
            }

            // Report how long it took to submit all tasks
            // Usually very fast even with mill sec
            System.out.println("All threads finished.");
            System.out.println("→ Observe memory usage for the next " + SLEEP_SEC + " seconds …");
            System.out.println("→ Press Ctrl+C when you're done watching");

            // Prevent main thread from exititng
            Thread.currentThread().join();
        }
    }

}
