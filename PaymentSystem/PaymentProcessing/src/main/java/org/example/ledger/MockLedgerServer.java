package org.example.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Local REST mock: records received requests and successful mock entries; performs no accounting. */
public final class MockLedgerServer implements AutoCloseable {
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final List<LedgerRequest> requests = new CopyOnWriteArrayList<>();
    private final List<LedgerRequest> entries = new CopyOnWriteArrayList<>();
    private final Map<UUID, RecordedOperation> operations = new HashMap<>();
    private final LedgerStatus outcome;
    private final Consumer<LedgerRequest> observer;
    private final Duration responseDelay;
    private final AtomicInteger transientFailures;

    public MockLedgerServer(int port, LedgerStatus outcome) throws IOException {
        this(port, outcome, request -> {}, Duration.ZERO);
    }

    public MockLedgerServer(int port, LedgerStatus outcome, Consumer<LedgerRequest> observer, Duration responseDelay)
            throws IOException {
        this(port, outcome, observer, responseDelay, 0);
    }

    public MockLedgerServer(int port, LedgerStatus outcome, Consumer<LedgerRequest> observer,
                            Duration responseDelay, int transientFailures) throws IOException {
        if (transientFailures < 0) throw new IllegalArgumentException("Transient failures cannot be negative");
        this.transientFailures = new AtomicInteger(transientFailures);
        this.outcome = Objects.requireNonNull(outcome, "outcome");
        this.observer = Objects.requireNonNull(observer, "observer");
        this.responseDelay = Objects.requireNonNull(responseDelay, "responseDelay");
        if (responseDelay.isNegative()) {
            throw new IllegalArgumentException("Response delay cannot be negative");
        }
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/ledger/entries", this::handle);
        server.setExecutor(executor);
        server.start();
    }

    public URI endpoint() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/ledger/entries");
    }

    public List<LedgerRequest> requests() {
        return List.copyOf(requests);
    }

    public List<LedgerRequest> entries() {
        return List.copyOf(entries);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!exchange.getRequestURI().getPath().equals("/ledger/entries")) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            if (!exchange.getRequestMethod().equals("POST")) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            LedgerRequest request;
            try {
                request = mapper.readValue(exchange.getRequestBody(), LedgerRequest.class);
                Objects.requireNonNull(request);
            } catch (IOException | RuntimeException exception) {
                send(exchange, 400, new LedgerResponse(LedgerStatus.FAILURE, null, "INVALID_REQUEST", "Invalid ledger request"));
                return;
            }
            List<String> keys = exchange.getRequestHeaders().get("Idempotency-Key");
            if (keys == null || keys.size() != 1 || !request.transactionId().toString().equals(keys.getFirst())) {
                send(exchange, 400, new LedgerResponse(LedgerStatus.FAILURE, null, "INVALID_IDEMPOTENCY_KEY",
                        "Idempotency-Key must equal the transaction ID"));
                return;
            }
            requests.add(request);
            RecordedOperation operation;
            boolean firstAttempt;
            synchronized (operations) {
                operation = operations.get(request.transactionId());
                firstAttempt = operation == null;
                if (firstAttempt) {
                    observer.accept(request);
                    LedgerResponse response = new LedgerResponse(outcome,
                            outcome == LedgerStatus.SUCCESS ? "entry-" + request.transactionId() : null,
                            outcome.name(), "Mock ledger " + outcome.name().toLowerCase(java.util.Locale.ROOT));
                    operation = new RecordedOperation(request, response);
                    if (outcome == LedgerStatus.SUCCESS) {
                        entries.add(request);
                    }
                    operations.put(request.transactionId(), operation);
                }
            }
            if (!sameRequest(operation.request(), request)) {
                send(exchange, 409, new LedgerResponse(LedgerStatus.UNKNOWN, null,
                        "IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_REQUEST", "Ledger request conflicts with its saved operation"));
                return;
            }
            // Simulate an applied update whose successful response was lost.
            if (outcome == LedgerStatus.SUCCESS && transientFailures.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
                exchange.getResponseHeaders().set("Retry-After", "0");
                send(exchange, 503, new LedgerResponse(LedgerStatus.UNKNOWN, null, "UNAVAILABLE", "Response unavailable"));
                return;
            }
            if (firstAttempt) {
                try {
                    Thread.sleep(responseDelay);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            send(exchange, operation.response().status() == LedgerStatus.PENDING ? 202 : 200, operation.response());
        }
    }

    private static boolean sameRequest(LedgerRequest first, LedgerRequest second) {
        return first.accountId().equals(second.accountId()) && first.paymentId().equals(second.paymentId())
                && first.transactionId().equals(second.transactionId()) && first.currency().equals(second.currency())
                && first.amount().compareTo(second.amount()) == 0;
    }

    private record RecordedOperation(LedgerRequest request, LedgerResponse response) {}

    private void send(HttpExchange exchange, int status, LedgerResponse response) throws IOException {
        byte[] body = mapper.writeValueAsBytes(response);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
