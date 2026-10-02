package org.killbill.billing.payment.http;

import org.killbill.billing.payment.checkout.CheckoutCallContext;
import org.killbill.billing.payment.checkout.IdempotencyConflictException;
import org.killbill.billing.payment.api.PaymentApiException;
import org.killbill.billing.payment.checkout.CheckoutResult;
import org.killbill.billing.payment.checkout.CheckoutService;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@RestController
@RequestMapping("/api/payments")
public class CheckoutController {
    private static final System.Logger LOGGER = System.getLogger(CheckoutController.class.getName());
    private final CheckoutService checkoutService;

    public CheckoutController(CheckoutService checkoutService) {
        this.checkoutService = Objects.requireNonNull(checkoutService, "checkoutService");
    }

    @PostMapping("/checkout")
    public CheckoutResult checkout(@RequestBody CheckoutRequest request,
            @RequestHeader HttpHeaders headers)
            throws PaymentApiException {
        List<String> idempotencyKeys = headers.get("Idempotency-Key");
        if (idempotencyKeys == null || idempotencyKeys.size() != 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Exactly one Idempotency-Key header is required");
        }
        CheckoutCallContext context;
        try {
            context = new CheckoutCallContext(UUID.randomUUID(), idempotencyKeys.getFirst());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
        if (request == null || request.accountId() == null || request.paymentMethodId() == null
                || request.amount() == null || request.amount().signum() <= 0 || request.currency() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Account ID, payment method ID, positive amount, and currency are required");
        }
        return checkoutService.checkout(request::accountId, request.paymentMethodId(), request.amount(),
                request.currency(), context);
    }

    @ExceptionHandler(PaymentApiException.class)
    public ProblemDetail paymentError(PaymentApiException exception) {
        LOGGER.log(System.Logger.Level.ERROR, "Checkout execution failed", exception);
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "Unable to complete checkout. This does not confirm that the payment failed; do not submit another payment.");
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ProblemDetail idempotencyConflict(IdempotencyConflictException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
        problem.setProperty("code", "IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_REQUEST");
        return problem;
    }

    public record CheckoutRequest(UUID accountId, UUID paymentMethodId, BigDecimal amount, Currency currency) {}

}
