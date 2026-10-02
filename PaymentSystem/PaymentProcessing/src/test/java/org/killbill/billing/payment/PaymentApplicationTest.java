package org.killbill.billing.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.ledger.MockLedgerServer;
import org.example.plugin.MockAuthorizationPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PaymentApplicationTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();
    private static final String REQUEST = """
            {"accountId":"11111111-1111-1111-1111-111111111111",
             "paymentMethodId":"22222222-2222-2222-2222-222222222222",
             "amount":12.34,"currency":"USD"}
            """;

    @ParameterizedTest
    @CsvSource({"SUCCESS,SUCCESS,SUCCEEDED,2", "FAILURE,SUCCESS,FAILED,1",
            "PENDING,SUCCESS,UNKNOWN,1", "UNKNOWN,SUCCESS,UNKNOWN,1",
            "SUCCESS,FAILURE,FAILED,2", "SUCCESS,PENDING,UNKNOWN,2", "SUCCESS,UNKNOWN,UNKNOWN,2"})
    void checkoutOverHttpAndReadAfterRestart(String authorization, String ledger, String expected, int count) throws Exception {
        String paymentId;
        JsonNode saved;
        JsonNode checkoutResponse;
        String key = UUID.randomUUID().toString();
        try (var client = HttpClient.newHttpClient(); var app = start(authorization, ledger)) {
            var response = post(client, app, REQUEST, key);
            assertEquals(200, response.statusCode(), response.body());
            var result = mapper.readTree(response.body());
            checkoutResponse = result;
            var duplicate = post(client, app, REQUEST.replace("12.34", "12.3400"), key);
            assertEquals(200, duplicate.statusCode());
            assertEquals(result, mapper.readTree(duplicate.body()));
            assertEquals(expected, result.path("status").asText());
            paymentId = result.path("paymentId").asText();
            var read = get(client, app, paymentId);
            assertEquals(200, read.statusCode(), read.body());
            saved = mapper.readTree(read.body());
            assertEquals(expected, saved.path("status").asText());
            assertEquals(result.path("stage"), saved.path("stage"));
            assertEquals(count, saved.path("transactions").size());
            assertEquals(result.path("authorizationTransactionId"), saved.path("transactions").get(0).path("transactionId"));
            assertEquals("AUTHORIZE", saved.path("transactions").get(0).path("type").asText());
            assertEquals(1, app.getBean(MockAuthorizationPlugin.class).authorizationCalls());
            assertEquals(count - 1, app.getBean(MockLedgerServer.class).requests().size());
            if (count == 2) {
                assertEquals("SUCCESS", saved.path("transactions").get(0).path("status").asText());
                assertEquals("CAPTURE", saved.path("transactions").get(1).path("type").asText());
                assertEquals(result.path("captureTransactionId"), saved.path("transactions").get(1).path("transactionId"));
            }
            if (expected.equals("SUCCEEDED")) {
                for (var transaction : saved.path("transactions")) {
                    assertEquals("SUCCESS", transaction.path("status").asText());
                    assertFalse(transaction.path("processedAt").isNull());
                }
            }
        }
        try (var client = HttpClient.newHttpClient(); var app = start("SUCCESS", "SUCCESS")) {
            var read = get(client, app, paymentId);
            assertEquals(200, read.statusCode());
            assertEquals(saved, mapper.readTree(read.body()));
            var duplicate = post(client, app, REQUEST, key);
            assertEquals(200, duplicate.statusCode());
            assertEquals(checkoutResponse, mapper.readTree(duplicate.body()));
            assertEquals(0, app.getBean(MockAuthorizationPlugin.class).authorizationCalls());
            assertTrue(app.getBean(MockLedgerServer.class).requests().isEmpty());
        }
    }

    @Test
    void invalidRequestsAndMissingPaymentsDoNotReachAdapters() throws Exception {
        try (var client = HttpClient.newHttpClient(); var app = start("SUCCESS", "SUCCESS")) {
            assertEquals(400, post(client, app, REQUEST.replace("12.34", "-1")).statusCode());
            assertEquals(400, post(client, app, "{}").statusCode());
            assertEquals(400, get(client, app, "invalid-uuid").statusCode());
            assertEquals(404, get(client, app, UUID.randomUUID().toString()).statusCode());
            assertEquals(0, app.getBean(MockAuthorizationPlugin.class).authorizationCalls());
            assertTrue(app.getBean(MockLedgerServer.class).requests().isEmpty());
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"account", "method", "amount", "currency"})
    void mismatchedKeyReturnsConflictWithoutChangingOriginalPayment(String field) throws Exception {
        try (var client = HttpClient.newHttpClient(); var app = start("SUCCESS", "SUCCESS")) {
            String key = UUID.randomUUID().toString();
            var original = mapper.readTree(post(client, app, REQUEST, key).body());
            String id = original.path("paymentId").asText();
            String before = get(client, app, id).body();
            String changed = switch (field) {
                case "account" -> REQUEST.replace("11111111-1111-1111-1111-111111111111", UUID.randomUUID().toString());
                case "method" -> REQUEST.replace("22222222-2222-2222-2222-222222222222", UUID.randomUUID().toString());
                case "amount" -> REQUEST.replace("12.34", "20.00");
                case "currency" -> REQUEST.replace("USD", "EUR");
                default -> throw new AssertionError(field);
            };
            var conflict = post(client, app, changed, key);
            assertEquals(409, conflict.statusCode(), conflict.body());
            var problem = mapper.readTree(conflict.body());
            assertEquals("IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_REQUEST", problem.path("code").asText());
            assertFalse(conflict.body().contains(id));
            assertEquals(before, get(client, app, id).body());
            assertEquals(1, app.getBean(MockAuthorizationPlugin.class).authorizationCalls());
            assertEquals(1, app.getBean(MockLedgerServer.class).requests().size());
            assertEquals(1, app.getBean(org.example.persistence.FilePaymentRepository.class).findAll().size());
        }
    }

    @Test
    void differentKeysAllowDifferentPaymentsWithTheSameDetails() throws Exception {
        try (var client = HttpClient.newHttpClient(); var app = start("SUCCESS", "SUCCESS")) {
            var first = mapper.readTree(post(client, app, REQUEST).body());
            var second = mapper.readTree(post(client, app, REQUEST).body());
            assertNotEquals(first.path("paymentId"), second.path("paymentId"));
            assertEquals(2, app.getBean(MockAuthorizationPlugin.class).authorizationCalls());
            assertEquals(2, app.getBean(MockLedgerServer.class).requests().size());
        }
    }

    @ParameterizedTest
    @CsvSource({"2,2,SUCCEEDED,CAPTURE,3,3,2", "10,0,UNKNOWN,AUTHORIZATION,3,0,1",
            "0,10,UNKNOWN,CAPTURE,1,3,2"})
    void retriesReuseIntentAndPreserveUncertainty(int authFailures, int ledgerFailures, String status,
                                                 String stage, int authCalls, int ledgerCalls, int transactions) throws Exception {
        try (var client = HttpClient.newHttpClient(); var app = start("SUCCESS", "SUCCESS",
                "--payment.authorization.mock.transient-failures=" + authFailures,
                "--payment.ledger.mock.transient-failures=" + ledgerFailures,
                "--payment.retry.defaults.initial-delay=1ms")) {
            String key = UUID.randomUUID().toString();
            var response = post(client, app, REQUEST, key);
            assertEquals(200, response.statusCode(), response.body());
            var result = mapper.readTree(response.body());
            assertEquals(status, result.path("status").asText());
            assertEquals(stage, result.path("stage").asText());
            var saved = mapper.readTree(get(client, app, result.path("paymentId").asText()).body());
            assertEquals(transactions, saved.path("transactions").size());
            for (var transaction : saved.path("transactions")) {
                boolean unresolved = status.equals("UNKNOWN") && transaction == saved.path("transactions").get(transactions - 1);
                assertEquals(unresolved ? "UNKNOWN" : "SUCCESS", transaction.path("status").asText());
                assertTrue(transaction.path("state").asText().endsWith(unresolved ? "REQUESTED" : "COMPLETED"));
            }
            assertEquals(result, mapper.readTree(post(client, app, REQUEST, key).body()));
            var plugin = app.getBean(MockAuthorizationPlugin.class);
            assertEquals(authCalls, plugin.authorizationCalls());
            assertEquals(1, plugin.holds());
            var ledger = app.getBean(MockLedgerServer.class);
            assertEquals(ledgerCalls, ledger.requests().size());
            assertEquals(ledgerCalls == 0 ? 0 : 1, ledger.entries().size());
            if (ledgerCalls > 0) {
                assertEquals(1, ledger.requests().stream().map(org.example.ledger.LedgerRequest::transactionId).distinct().count());
            }
        }
    }

    private ServletWebServerApplicationContext start(String authorization, String ledger, String... overrides) {
        var arguments = new java.util.ArrayList<>(java.util.List.of(
                "--server.port=0", "--payment.store.path=" + directory.resolve("payments.json"),
                "--payment.authorization.mock.outcome=" + authorization, "--payment.ledger.mock.outcome=" + ledger,
                "--spring.main.banner-mode=off", "--logging.level.root=WARN"));
        arguments.addAll(java.util.List.of(overrides));
        return (ServletWebServerApplicationContext) SpringApplication.run(PaymentApplication.class, arguments.toArray(String[]::new));
    }

    private HttpResponse<String> post(HttpClient client, ServletWebServerApplicationContext app, String body) throws Exception {
        return post(client, app, body, UUID.randomUUID().toString());
    }

    private HttpResponse<String> post(HttpClient client, ServletWebServerApplicationContext app, String body, String key) throws Exception {
        return client.send(HttpRequest.newBuilder(endpoint(app, "checkout")).timeout(Duration.ofSeconds(10))
                .header("Idempotency-Key", key)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(HttpClient client, ServletWebServerApplicationContext app, String id) throws Exception {
        return client.send(HttpRequest.newBuilder(endpoint(app, id)).timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private URI endpoint(ServletWebServerApplicationContext app, String suffix) {
        return URI.create("http://127.0.0.1:" + app.getWebServer().getPort() + "/api/payments/" + suffix);
    }
}
