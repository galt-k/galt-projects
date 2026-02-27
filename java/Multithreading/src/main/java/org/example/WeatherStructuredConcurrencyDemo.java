package org.example;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.StructuredTaskScope.Subtask;

public class WeatherStructuredConcurrencyDemo {
    private static final int TIMEOUT_SECONDS = 5;

    //Simulated SLOW /failing API calls
    private static String getCurrent() throws InterruptedException {
        Thread.sleep(1200);
        return "Current: 22C, Sunny";
    }

    private static String getForecast() throws InterruptedException {
        Thread.sleep(1800);
        return "Forecast: Tomorrow 24C, Partly Cloudy";
    }

    private static String getHistory() throws Exception {
        Thread.sleep(800);
        return "History: Rainy";
    }

    static String fetchUnstructured() throws Exception {
        System.out.println("Fetching unstructured...");

        //Use virtual threads
        try (ExecutorService es = Executors.newVirtualThreadPerTaskExecutor()) {

            //submit() returns a Future- we must store refernces manullay to get the results later
            Future<String> currentFuture = es.submit(WeatherStructuredConcurrencyDemo::getCurrent);
            Future<String> forecastFuture = es.submit(WeatherStructuredConcurrencyDemo::getForecast);
            Future<String> historyFuture = es.submit(WeatherStructuredConcurrencyDemo::getHistory);

            //.get() blocks sequentially
            String current = currentFuture.get();
            String forecast = forecastFuture.get();
            String history = historyFuture.get();
            return new Result(current, forecast, history).toString();
        }
        // THought: no automatic cancellation- if eception or timeout, remaining tasks
    }

    static String fetchCompletableFuture() throws Exception {
        System.out.println("Fetching CompletableFuture...");
        // using the same virtual thread executor
        try (ExecutorService es = Executors.newVirtualThreadPerTaskExecutor()) {
            //1. Kick off all the tasks asynchronously
            //CompletableFuture<String> currentCF = CompletableFuture.supplyAsync(WeatherStructuredConcurrencyDemo::getCurrent, es);
            //CompletableFuture<String> forecastCF = CompletableFuture.supplyAsync(WeatherStructuredConcurrencyDemo::getForecast, es);
            //CompletableFuture<String> historyCF = CompletableFuture.supplyAsync(WeatherStructuredConcurrencyDemo::getHistory, es);

            var currentCF = CompletableFuture.supplyAsync(() -> {
                try { return WeatherStructuredConcurrencyDemo.getCurrent(); } catch (Exception e) { throw new RuntimeException(e); }
            }, es);

            var forecastCF = CompletableFuture.supplyAsync(() -> {
                try { return WeatherStructuredConcurrencyDemo.getForecast(); } catch (Exception e) { throw new RuntimeException(e); }
            }, es);

            var historyCF = CompletableFuture.supplyAsync(() -> {
                try { return WeatherStructuredConcurrencyDemo.getHistory(); } catch (Exception e) { throw new RuntimeException(e); }
            }, es);


            //2. Combine them all into one mega future
            CompletableFuture<Void> allTasks = CompletableFuture.allOf(currentCF, forecastCF, historyCF);

            //3. Wait for the aggregate to finish with a timeout
            try {
                allTasks.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                System.err.println("Timed out waiting for all tasks to complete.");
                //manual cleanup: we have to cancel them manuallly
                currentCF.cancel(true);
                forecastCF.cancel(true);
                historyCF.cancel(true);
            }

            //4. Extract results
            String current = currentCF.getNow("Current weather unavialble");
            String forecast = forecastCF.getNow("Current weather unavialble");
            String history = historyCF.getNow("Current weather unavialble");

            return new Result(current, forecast, history).toString();
        }
    }

    static String fetchStructuredAllMustSuceed() throws Throwable {
        System.out.println("Fetching structured all must suceed...");

        try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
            // fork() launches on virtual thread- cheap and automatic
            // Returns Subtask supplier- we can call .get() later if successful
            Subtask<String> currentTask = scope.fork(WeatherStructuredConcurrencyDemo::getCurrent);
            Subtask<String> forecastTask = scope.fork(WeatherStructuredConcurrencyDemo::getForecast);
            Subtask<String> historyTask = scope.fork(WeatherStructuredConcurrencyDemo::getHistory);
            // join blocks until all complete OR first failure occurs
            // If failure- scope shuts down, cancels remaining subtasks automatically
            scope.join();
            //rethrows first exception- clean propagation to caller
            // no need to manully inspect each subtask for exceptions
            scope.throwIfFailed();

            String current = currentTask.get();
            String forecast = forecastTask.get();
            String history = historyTask.get();

            return new Result(current, forecast, history).toString();
        }
    }

    static String fetchStructuredWithTimeout() throws Exception {
        System.out.println("Fetching structured all must suceed...");
        // Use plain StructuredTaskScope - we handle shutdown ourselves for partial success
        try (var scope = new StructuredTaskScope<String>()) {
            Subtask<String> currentTask = scope.fork(WeatherStructuredConcurrencyDemo::getCurrent);
            Subtask<String> forecastTask = scope.fork(WeatherStructuredConcurrencyDemo::getForecast);
            Subtask<String> historyTask = scope.fork(WeatherStructuredConcurrencyDemo::getHistory);

            scope.joinUntil(Instant.now().plusSeconds(TIMEOUT_SECONDS));

            var results = new StringBuilder();
            for (Subtask<String> task: List.of(currentTask, forecastTask, historyTask)) {
                switch (task.state()) {
                    case SUCCESS -> results.append(task.get()).append("\n");
                    case FAILED -> results.append("Failed: ").append(task.exception().getMessage()).append("\n");
                    case UNAVAILABLE -> results.append("Timeout / cancelled\n");
                }
            }
            return results.toString();
        }
    }

    //record = immutable data holder
    record Result(String current, String forecast, String history) {
        @Override public String toString() {
            return "Result:\n " + current + "\n + " +  forecast + "\n + " + history;
        }
    }

    public static void main(String[] args) throws Throwable {
        //System.out.println(fetchStructuredAllMustSuceed());
        //System.out.println(fetchUnstructured());
        //System.out.println(fetchStructuredWithTimeout());
        System.out.println(fetchCompletableFuture());
    }
}
