package org.killbill.billing.payment.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.PaymentAPIException;
import org.example.persistence.FilePaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import org.example.TransactionStatus;
import org.example.TransactionType;
import org.example.persistence.PaymentTransactionModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.killbill.billing.payment.checkout.CheckoutService;
import org.killbill.billing.payment.checkout.CheckoutCallContext;
import org.killbill.billing.payment.core.PaymentAutomatonRunner;
import org.killbill.billing.payment.core.PaymentCoreApi;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class CheckoutControllerTest {
    @TempDir
    Path directory;
    private FilePaymentRepository repository;

    @BeforeEach
    void openRepository() {
        repository = new FilePaymentRepository(directory.resolve("payments.json"));
    }

    @AfterEach
    void closeRepository() {
        repository.close();
    }

    private static final String REQUEST = """
            {"accountId":"11111111-1111-1111-1111-111111111111",
             "paymentMethodId":"22222222-2222-2222-2222-222222222222",
             "amount":12.34,"currency":"USD"}
            """;
    private final ObjectMapper mapper = new ObjectMapper();
    private static final String KEY = "checkout_abc-123";

    @ParameterizedTest
    @CsvSource({
            "SUCCESS, SUCCESS, SUCCEEDED, CAPTURE",
            "FAILURE, SUCCESS, FAILED, AUTHORIZATION",
            "UNKNOWN, SUCCESS, UNKNOWN, AUTHORIZATION",
            "SUCCESS, FAILURE, FAILED, CAPTURE",
            "SUCCESS, UNKNOWN, UNKNOWN, CAPTURE"
    })
    void returnsBusinessOutcome(TransactionStatus authorizationStatus, TransactionStatus captureStatus,
                                String expectedStatus, String expectedStage) throws Exception {
        List<TransactionType> calls = new ArrayList<>();
        MockMvc mvc = mvc(context -> {
            calls.add(context.getTransactionType());
            assertNotNull(context.getCallContext());
            assertEquals(KEY, assertInstanceOf(CheckoutCallContext.class, context.getCallContext()).idempotencyKey());
            TransactionStatus outcome = switch (context.getTransactionType()) {
                case AUTHORIZE -> authorizationStatus;
                case CAPTURE -> captureStatus;
                default -> throw new AssertionError("Unexpected operation");
            };
            context.setPaymentTransaction(new PaymentTransactionModel(UUID.randomUUID(), context.getAccountId(),
                    context.getPaymentId(), context.getTransactionType(), context.getAmount(), context.getCurrency(),
                    outcome, null, null, null, Instant.now(), Instant.now()));
        });
        MvcResult response = postCheckout(mvc, REQUEST);
        assertEquals(200, response.getResponse().getStatus());
        JsonNode body = mapper.readTree(response.getResponse().getContentAsString());
        assertEquals(expectedStatus, body.get("status").asText());
        assertEquals(expectedStage, body.get("stage").asText());
        var stored = repository.findById(UUID.fromString(body.get("paymentId").asText())).orElseThrow();
        assertEquals(expectedStatus, stored.getPaymentState().name());
        assertEquals(expectedStage, stored.getPaymentStage().name());
        assertNotNull(UUID.fromString(body.get("paymentId").asText()));
        assertNotNull(UUID.fromString(body.get("authorizationTransactionId").asText()));
        assertEquals(expectedStage.equals("CAPTURE") ? 2 : 1, calls.size());
        assertEquals(expectedStage.equals("AUTHORIZATION"), body.get("captureTransactionId").isNull());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "{", "{\"accountId\":\"bad-uuid\"}"})
    void rejectsMalformedOrMissingFields(String request) throws Exception {
        assertEquals(400, postCheckout(mvc(context -> fail("Invalid request reached the runner")), request)
                .getResponse().getStatus());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "null"})
    void rejectsInvalidAmounts(String amount) throws Exception {
        assertEquals(400, postCheckout(mvc(context -> fail("Invalid amount reached the runner")),
                REQUEST.replace("12.34", amount)).getResponse().getStatus());
    }

    @Test
    void doesNotReportInfrastructureErrorsAsDeclinesOrLeakDetails() throws Exception {
        MvcResult response = postCheckout(mvc(context -> {
            throw new PaymentAPIException("Database credentials are secret", null);
        }), REQUEST);
        assertEquals(500, response.getResponse().getStatus());
        String body = response.getResponse().getContentAsString();
        assertFalse(body.contains("secret"));
        assertTrue(body.contains("does not confirm that the payment failed"));
    }

    private MockMvc mvc(PaymentAutomatonRunner runner) {
        return MockMvcBuilders.standaloneSetup(
                new CheckoutController(new CheckoutService(new PaymentCoreApi(runner), repository))).build();
    }

    @Test
    void rejectsMissingOrRepeatedKeyBeforeCreatingPayment() throws Exception {
        var mvc = mvc(context -> fail("Invalid header reached runner"));
        assertEquals(400, mvc.perform(post("/api/payments/checkout")
                .contentType(MediaType.APPLICATION_JSON).content(REQUEST)).andReturn().getResponse().getStatus());
        assertEquals(400, mvc.perform(post("/api/payments/checkout").header("Idempotency-Key", KEY, KEY)
                .contentType(MediaType.APPLICATION_JSON).content(REQUEST)).andReturn().getResponse().getStatus());
        assertTrue(repository.findAll().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", " key", "key ", "a,b", "key,", "a b", "key/123", "clé",
            "12345678901234567890123456789012345678901234567890123456789012345"})
    void rejectsInvalidKeyBeforeCreatingPayment(String key) throws Exception {
        var mvc = mvc(context -> fail("Invalid header reached runner"));
        assertEquals(400, mvc.perform(post("/api/payments/checkout").header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(REQUEST)).andReturn().getResponse().getStatus());
        assertTrue(repository.findAll().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"A", "11111111-1111-4111-8111-111111111111",
            "1234567890123456789012345678901234567890123456789012345678901234"})
    void preservesValidKeyAndAcceptsCaseInsensitiveHeaderName(String key) throws Exception {
        var mvc = mvc(context -> {
            var call = assertInstanceOf(CheckoutCallContext.class, context.getCallContext());
            assertEquals(key, call.idempotencyKey());
            assertNotNull(call.requestId());
            context.setPaymentTransaction(new PaymentTransactionModel(UUID.randomUUID(), context.getAccountId(),
                    context.getPaymentId(), context.getTransactionType(), context.getAmount(), context.getCurrency(),
                    TransactionStatus.FAILURE, null, null, null, Instant.now(), Instant.now()));
        });
        assertEquals(200, mvc.perform(post("/api/payments/checkout").header("idempotency-key", key)
                .contentType(MediaType.APPLICATION_JSON).content(REQUEST)).andReturn().getResponse().getStatus());
    }

    private static MvcResult postCheckout(MockMvc mvc, String request) throws Exception {
        return mvc.perform(post("/api/payments/checkout")
                .header("Idempotency-Key", KEY)
                .contentType(MediaType.APPLICATION_JSON).content(request)).andReturn();
    }
}
