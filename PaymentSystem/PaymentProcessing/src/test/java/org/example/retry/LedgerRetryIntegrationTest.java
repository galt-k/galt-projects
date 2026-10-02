package org.example.retry;

import com.sun.net.httpserver.HttpServer;
import org.example.ledger.LedgerException;
import org.example.ledger.LedgerRequest;
import org.example.ledger.LedgerStatus;
import org.example.ledger.RestLedgerClient;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.killbill.billing.payment.core.RetryConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

class LedgerRetryIntegrationTest {
    @ParameterizedTest
    @CsvSource({"503,true,0,3", "429,true,0,3", "503,false,0,1",
            "503,true,3600,1", "409,true,0,1", "500,true,0,1", "202,true,0,1"})
    void retryRespectsHttpOutcomeProviderDelayAndIdempotencyContract(int status, boolean safe,
                                                                   String retryAfter, int expectedCalls) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> keys = new CopyOnWriteArrayList<>();
        List<String> bodies = new CopyOnWriteArrayList<>();
        server.createContext("/entries", exchange -> {
            try (exchange) {
                keys.add(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
                bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                exchange.getResponseHeaders().set("Retry-After", retryAfter);
                exchange.sendResponseHeaders(status, -1);
            }
        });
        server.start();
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                    "payment.retry.defaults.initial-delay", "1ms", "payment.retry.defaults.max-attempts", "3")));
            context.register(RetryConfiguration.class);
            context.registerBean(RestLedgerClient.class, () -> new RestLedgerClient(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/entries"), Duration.ofSeconds(1), safe));
            context.refresh();
            var client = context.getBean(RestLedgerClient.class);
            var request = new LedgerRequest(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    BigDecimal.TEN, Currency.getInstance("USD"));
            if (status == 202) {
                assertEquals(LedgerStatus.PENDING, client.postEntry(request).status());
            } else {
                assertEquals(status, assertThrows(LedgerException.class, () -> client.postEntry(request)).httpStatus());
            }
            assertEquals(expectedCalls, keys.size());
            assertTrue(keys.stream().allMatch(request.transactionId().toString()::equals));
            assertEquals(1, bodies.stream().distinct().count());
        } finally {
            server.stop(0);
        }
    }
}
