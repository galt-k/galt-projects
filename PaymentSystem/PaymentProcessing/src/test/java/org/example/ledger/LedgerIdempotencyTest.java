package org.example.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Currency;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class LedgerIdempotencyTest {
    private final LedgerRequest request = new LedgerRequest(UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), new BigDecimal("10.00"), Currency.getInstance("USD"));

    @Test
    void concurrentRestAttemptsPostOnceAndNewTransactionPostsSeparately() throws Exception {
        try (var server = new MockLedgerServer(0, LedgerStatus.SUCCESS);
             var client = new RestLedgerClient(server.endpoint(), Duration.ofSeconds(5));
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var attempts = new ArrayList<Future<LedgerResponse>>();
            for (int i = 0; i < 20; i++) attempts.add(executor.submit(() -> client.postEntry(request)));
            var first = attempts.getFirst().get(10, TimeUnit.SECONDS);
            for (var attempt : attempts) assertEquals(first, attempt.get(10, TimeUnit.SECONDS));
            assertEquals(20, server.requests().size());
            assertEquals(1, server.entries().size());
            client.postEntry(new LedgerRequest(request.accountId(), request.paymentId(), UUID.randomUUID(), request.amount(), request.currency()));
            assertEquals(2, server.entries().size());
        }
    }

    @ParameterizedTest
    @EnumSource(LedgerStatus.class)
    void repeatedRequestsReplayOutcome(LedgerStatus status) throws Exception {
        try (var server = new MockLedgerServer(0, status);
             var client = new RestLedgerClient(server.endpoint(), Duration.ofSeconds(2))) {
            var original = client.postEntry(request);
            assertEquals(original, client.postEntry(new LedgerRequest(request.accountId(), request.paymentId(), request.transactionId(),
                    new BigDecimal("10.000"), request.currency())));
            assertEquals(status == LedgerStatus.SUCCESS ? 1 : 0, server.entries().size());
        }
    }

    @Test
    void retryAfterResponseTimeoutRecoversOriginalReceiptWithoutAnotherPosting() throws Exception {
        try (var server = new MockLedgerServer(0, LedgerStatus.SUCCESS, ignored -> {}, Duration.ofSeconds(2));
             var shortClient = new RestLedgerClient(server.endpoint(), Duration.ofMillis(500));
             var recoveryClient = new RestLedgerClient(server.endpoint(), Duration.ofSeconds(5))) {
            assertThrows(LedgerException.class, () -> shortClient.postEntry(request));
            assertEquals(1, server.entries().size(), "First request applied before its delayed response");
            var recovered = recoveryClient.postEntry(request);
            assertEquals(LedgerStatus.SUCCESS, recovered.status());
            assertEquals("entry-" + request.transactionId(), recovered.entryId());
            assertEquals(1, server.entries().size());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"account", "payment", "amount", "currency"})
    void changedRequestReturns409AndClientDoesNotTreatItAsFinancialFailure(String field) throws Exception {
        try (var server = new MockLedgerServer(0, LedgerStatus.SUCCESS);
             var client = new RestLedgerClient(server.endpoint(), Duration.ofSeconds(2));
             var http = HttpClient.newHttpClient()) {
            client.postEntry(request);
            var changed = new LedgerRequest(field.equals("account") ? UUID.randomUUID() : request.accountId(),
                    field.equals("payment") ? UUID.randomUUID() : request.paymentId(), request.transactionId(),
                    field.equals("amount") ? BigDecimal.ONE : request.amount(),
                    field.equals("currency") ? Currency.getInstance("EUR") : request.currency());
            var response = http.send(HttpRequest.newBuilder(server.endpoint())
                    .header("Idempotency-Key", request.transactionId().toString())
                    .POST(HttpRequest.BodyPublishers.ofString(new ObjectMapper().writeValueAsString(changed))).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(409, response.statusCode());
            assertThrows(LedgerException.class, () -> client.postEntry(changed));
            assertEquals(1, server.entries().size());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "different", "duplicate"})
    void rejectsMissingOrMismatchedHeaderWithoutPosting(String invalid) throws Exception {
        try (var server = new MockLedgerServer(0, LedgerStatus.SUCCESS); var http = HttpClient.newHttpClient()) {
            var builder = HttpRequest.newBuilder(server.endpoint());
            if (invalid.equals("different")) builder.header("Idempotency-Key", UUID.randomUUID().toString());
            if (invalid.equals("duplicate")) builder.header("Idempotency-Key", request.transactionId().toString())
                    .header("Idempotency-Key", request.transactionId().toString());
            var response = http.send(builder.POST(HttpRequest.BodyPublishers.ofString(new ObjectMapper().writeValueAsString(request))).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(400, response.statusCode());
            assertTrue(server.entries().isEmpty());
        }
    }
}
