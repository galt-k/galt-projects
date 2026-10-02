package org.example.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.example.retry.AttemptOutcome;
import org.example.retry.LedgerRetryClassifier;
import org.example.retry.RetryableOperation;
import org.example.retry.RetryAfter;
import org.example.retry.RetryAttempt;
import java.time.Instant;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;

public class RestLedgerClient implements LedgerClient, AutoCloseable {
    private final URI endpoint;
    private final Duration timeout;
    private final HttpClient client;
    private final boolean idempotencyGuaranteed;
    private final ObjectMapper mapper = new ObjectMapper();

    public RestLedgerClient(URI endpoint, Duration timeout) {
        this(endpoint, timeout, false);
    }

    public RestLedgerClient(URI endpoint, Duration timeout, boolean idempotencyGuaranteed) {
        this.idempotencyGuaranteed = idempotencyGuaranteed;
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if ((!"http".equals(endpoint.getScheme()) && !"https".equals(endpoint.getScheme())) || endpoint.getHost() == null) {
            throw new IllegalArgumentException("Ledger endpoint must be an absolute HTTP(S) URI");
        }
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("Ledger timeout must be positive");
        }
        client = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override
    @RetryableOperation(policy = "capture-ledger", classifier = LedgerRetryClassifier.class)
    public LedgerResponse postEntry(LedgerRequest request) throws LedgerException {
        Objects.requireNonNull(request, "request");
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder(endpoint).timeout(RetryAttempt.timeoutWithin(timeout))
                    .header("Content-Type", "application/json").header("Accept", "application/json")
                    .header("Idempotency-Key", request.transactionId().toString())
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(request))).build();
            HttpResponse<String> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            int code = response.statusCode();
            // HTTP errors do not prove that the remote ledger failed to apply the update.
            if (code >= 500 || code < 200 || code >= 300 && code != 400 && code != 422) {
                var outcome = switch (code) {
                    case 429, 502, 503, 504 -> AttemptOutcome.Outcome.TRANSIENT_FAILURE;
                    case 409 -> AttemptOutcome.Outcome.IDEMPOTENCY_CONFLICT;
                    case 401, 403, 404 -> AttemptOutcome.Outcome.INVALID_REQUEST;
                    default -> AttemptOutcome.Outcome.UNKNOWN;
                };
                throw new LedgerException("Ledger outcome is uncertain after HTTP " + code, null, code,
                        new AttemptOutcome(outcome, AttemptOutcome.RetryAdvice.UNSPECIFIED, idempotencyGuaranteed,
                                RetryAfter.parse(response.headers().firstValue("Retry-After").orElse(null), Instant.now())));
            }
            if (code == 202) {
                return new LedgerResponse(LedgerStatus.PENDING, null, "202", "Ledger accepted the request for processing");
            }
            LedgerResponse result = mapper.readValue(response.body(), LedgerResponse.class);
            if (result == null || code >= 400 && result.status() != LedgerStatus.FAILURE) {
                throw new LedgerException("Ledger returned an unconfirmed outcome", null);
            }
            return result;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new LedgerException("Ledger request interrupted; outcome is uncertain", exception);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new LedgerException("Unable to confirm ledger outcome", exception);
        } catch (IOException exception) {
            throw new LedgerException("Ledger transport failed; outcome is uncertain", exception, null,
                    new AttemptOutcome(AttemptOutcome.Outcome.TRANSIENT_FAILURE,
                            AttemptOutcome.RetryAdvice.UNSPECIFIED, idempotencyGuaranteed, Duration.ZERO));
        }
    }

    @Override
    public void close() {
        client.close();
    }
}
