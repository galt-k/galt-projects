package org.example.ledger;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.Currency;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RestLedgerClientTest {
    @ParameterizedTest
    @CsvSource({"500,SUCCESS", "200,MALFORMED", "200,MISSING_RECEIPT", "400,MALFORMED", "302,SUCCESS", "204,EMPTY"})
    void doesNotTurnHttpErrorsOrInvalidResponsesIntoConfirmedOutcomes(int code, String kind) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/entries", exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                String body = switch (kind) {
                    case "MALFORMED" -> "not JSON";
                    case "EMPTY" -> "";
                    case "MISSING_RECEIPT" -> "{\"status\":\"SUCCESS\"}";
                    default -> "{\"status\":\"SUCCESS\",\"entryId\":\"entry-1\"}";
                };
                byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(code, code == 204 ? -1 : bytes.length);
                if (code != 204) {
                    exchange.getResponseBody().write(bytes);
                }
            }
        });
        server.start();
        try (var client = new RestLedgerClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/entries"), Duration.ofSeconds(2))) {
            assertThrows(LedgerException.class, () -> client.postEntry(new LedgerRequest(UUID.randomUUID(), UUID.randomUUID(),
                    UUID.randomUUID(), BigDecimal.TEN, Currency.getInstance("USD"))));
        } finally {
            server.stop(0);
        }
    }
}
