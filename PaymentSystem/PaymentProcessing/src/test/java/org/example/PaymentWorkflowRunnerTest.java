package org.example;

import org.example.persistence.PaymentWorkflowDAOHelper;

import org.example.persistence.PaymentTransactionModel;

import org.example.plugin.PluginProperty;

import org.example.statemachine.PaymentStateContext;

import java.util.ArrayList;
import java.util.List;
import org.example.plugin.PaymentPluginAPI;
import org.example.plugin.PaymentPluginAPIException;
import org.example.plugin.PaymentPluginStatus;
import org.example.plugin.PaymentTransactionInfoPlugin;
import org.example.states.CapturePending;
import org.example.states.Failed;
import org.example.states.Succeeded;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.UUID;
import org.example.states.Created;
import org.example.workflow.OperationResult;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * Standalone contract checks:
 * mvn test-compile
 * Run the main method with the module's runtime dependencies on the classpath.
 */
public class PaymentWorkflowRunnerTest {
    public static void main(String[] args) throws Exception {
        int checks = 0;
        for (TransactionType type : List.of(TransactionType.CHECKOUT, TransactionType.AUTHORIZE)) {
            verify(type, null, null);
            verify(type, "EXISTING_STATE", null);
            verify(type, null, "initiated");
            verify(type, null, "database");
            verify(type, null, "operation");
            verify(type, null, "plugin");
            verify(type, null, "completed");
            verify(type, null, "completed-database");
            verify(type, null, "pending");
            verify(type, null, "declined");
            verify(type, null, "canceled");
            verify(type, null, "missing-info");
            checks += 12;
        }
        System.out.println("Passed " + checks + " workflow entry-point checks");
    }

    private static void verify(TransactionType type, String currentState, String failureStage)
            throws Exception {
        List<String> calls = new ArrayList<>();
        PaymentAPIException failure = new PaymentAPIException("Expected failure", null);
        PaymentPluginAPIException pluginFailure = new PaymentPluginAPIException("Expected plugin failure");
        DataAccessResourceFailureException databaseFailure = new DataAccessResourceFailureException("Database unavailable");
        InternalCallContext internalContext = new InternalCallContext() {};
        PaymentStateContext context = PaymentTestContexts.create(internalContext, type);
        PaymentTransactionModel previousTransaction = PaymentTestContexts.transaction(context);
        context.setPaymentTransaction(previousTransaction);
        PaymentTransactionModel insertedTransaction = PaymentTestContexts.transaction(context);
        String initialState = "INITIAL_" + type;
        String expectedState = currentState == null ? initialState : currentState;
        Operation operation = new Operation(type.name());

        class Actions {
            void perform(String stage, TransactionType actualType, PaymentStateContext actualContext)
                    throws PaymentAPIException {
                check(actualType == type || (stage.equals("operation")
                        && type == TransactionType.PURCHASE && actualType == TransactionType.CHECKOUT),
                        "Wrong transaction for " + stage);
                check(actualContext == context, "Context was not forwarded for " + stage);
                calls.add(stage);
                if ((stage.equals("initiated") && "database".equals(failureStage))
                        || (stage.equals("completed") && "completed-database".equals(failureStage))) {
                    throw databaseFailure;
                }
                if (stage.equals("operation")) {
                    check(context.getPaymentTransaction() == insertedTransaction,
                            "Inserted transaction must be in the context before gateway execution");
                    check(context.getPaymentTransaction().state() == TransactionState.forOutcome(type, TransactionStatus.UNKNOWN),
                            "Intent lifecycle must be REQUESTED before the gateway executes");
                }
                if (stage.equals(failureStage)) {
                    throw failure;
                }
            }
        }
        Actions actions = new Actions();
        PaymentWorkflowDAOHelper dao = new PaymentWorkflowDAOHelper(TestPaymentPersistence.dao(), TestPaymentPersistence.transactions()) {
            @Override
            public String getPaymentProviderPluginName(UUID accountId, UUID paymentMethodId, Boolean includeDeletedPaymentMethod) {
                check(Boolean.FALSE.equals(includeDeletedPaymentMethod), "Deleted methods must be excluded");
                calls.add("provider");
                return "provider-plugin";
            }

            @Override
            public PaymentTransactionModel insertPaymentTransaction(UUID accountId, UUID paymentId,
                    TransactionType actualType, BigDecimal amount, Currency currency, TransactionStatus status)
                    throws PaymentAPIException {
                check(accountId.equals(context.getAccountId()), "Wrong account ID");
                check(paymentId.equals(context.getPaymentId()), "Wrong payment ID");
                check(amount.equals(context.getAmount()), "Wrong amount");
                check(currency.equals(context.getCurrency()), "Wrong currency");
                check(status == TransactionStatus.UNKNOWN, "Intent must be UNKNOWN");
                actions.perform("initiated", actualType, context);
                return insertedTransaction;
            }

            @Override
            public java.time.Instant updatePaymentTransactionStatus(UUID accountId, UUID paymentId, UUID transactionId,
                    TransactionStatus status, String gatewayTransactionId, String gatewayResponseCode, String gatewayErrorMessage, org.example.plugin.PaymentPluginStatus gatewayStatus)
                    throws PaymentAPIException {
                check(accountId.equals(context.getAccountId()) && paymentId.equals(context.getPaymentId()),
                        "Outcome must use the original payment identifiers");
                check(transactionId.equals(insertedTransaction.id()), "Outcome must update the inserted transaction");
                TransactionStatus expectedStatus = switch (failureStage == null ? "" : failureStage) {
                    case "operation", "plugin" -> TransactionStatus.UNKNOWN;
                    case "declined", "canceled" -> TransactionStatus.FAILURE;
                    case "pending" -> TransactionStatus.UNKNOWN;
                    default -> TransactionStatus.SUCCESS;
                };
                check(status == expectedStatus, "Wrong persisted outcome: " + status);
                if ("operation".equals(failureStage) || "plugin".equals(failureStage)) {
                    check(gatewayTransactionId == null && gatewayResponseCode == null, "Do not invent gateway metadata");
                    check(gatewayErrorMessage.equals(context.getException().getMessage()), "Persist the original error message");
                } else if ("missing-info".equals(failureStage)) {
                    check(gatewayTransactionId == null && gatewayResponseCode == null && gatewayErrorMessage == null,
                            "Absent gateway metadata must not prevent persistence");
                } else {
                    check(gatewayTransactionId.equals("gateway-transaction"), "Wrong gateway transaction ID");
                    check(gatewayResponseCode.equals("gateway-code"), "Wrong gateway response code");
                    check(gatewayErrorMessage.equals("gateway-message"), "Wrong gateway error message");
                }
                actions.perform("completed", type, context);
                return java.time.Instant.parse("2026-09-29T12:00:00Z");
            }
        };
        PaymentPluginAPI plugin = new PaymentPluginAPI() {
            @Override
            public PaymentPluginStatus checkoutPayment(UUID accountId, UUID paymentId, UUID transactionId, UUID paymentMethodId,
                    BigDecimal amount, Currency currency, Iterable<PluginProperty> properties, CallContext callContext)
                    throws PaymentPluginAPIException, PaymentAPIException {
                return execute(TransactionType.CHECKOUT, accountId, paymentId, transactionId, paymentMethodId, amount, currency, properties, callContext);
            }

            @Override
            public PaymentPluginStatus authorizePayment(UUID accountId, UUID paymentId, UUID transactionId, UUID paymentMethodId,
                    BigDecimal amount, Currency currency, Iterable<PluginProperty> properties, CallContext callContext)
                    throws PaymentPluginAPIException, PaymentAPIException {
                return execute(TransactionType.AUTHORIZE, accountId, paymentId, transactionId, paymentMethodId, amount, currency, properties, callContext);
            }

            @Override
            public PaymentPluginStatus capturePayment(UUID accountId, UUID paymentId, UUID transactionId, UUID paymentMethodId,
                    BigDecimal amount, Currency currency, Iterable<PluginProperty> properties, CallContext callContext)
                    throws PaymentPluginAPIException, PaymentAPIException {
                return execute(TransactionType.CAPTURE, accountId, paymentId, transactionId, paymentMethodId, amount, currency, properties, callContext);
            }

            private PaymentPluginStatus execute(TransactionType actualType, UUID accountId, UUID paymentId, UUID transactionId, UUID paymentMethodId,
                    BigDecimal amount, Currency currency, Iterable<PluginProperty> properties, CallContext callContext)
                    throws PaymentPluginAPIException, PaymentAPIException {
                check(accountId.equals(context.getAccountId()), "Wrong plugin account ID");
                check(paymentId.equals(context.getPaymentId()), "Wrong plugin payment ID");
                check(transactionId.equals(insertedTransaction.id()), "Plugin must receive the persisted transaction ID");
                check(paymentMethodId.equals(context.getPaymentMethodId()), "Wrong payment method ID");
                check(amount.equals(context.getAmount()) && currency.equals(context.getCurrency()), "Wrong payment amount or currency");
                check(properties == context.getProperties(), "Properties must be forwarded unchanged");
                check(callContext == context.getCallContext(), "Call context must be forwarded unchanged");
                actions.perform("operation", actualType, context);
                if ("plugin".equals(failureStage)) {
                    throw pluginFailure;
                }
                if (!"missing-info".equals(failureStage)) {
                    context.setPaymentInfoPlugin(new PaymentTransactionInfoPlugin("gateway-transaction", "gateway-code", "gateway-message"));
                }
                return switch (failureStage == null ? "" : failureStage) {
                    case "pending" -> PaymentPluginStatus.PENDING;
                    case "declined" -> PaymentPluginStatus.ERROR;
                    case "canceled" -> PaymentPluginStatus.CANCELED;
                    default -> PaymentPluginStatus.PROCESSED;
                };
            }
        };

        // The state implementation controls lifecycle execution, as required by its contract.
        WorkflowState state = (actualOperation, operationCallback, enteringCallback, leavingCallback) -> {
            check(actualOperation == operation, "Wrong operation delegated to the state");
            calls.add("lifecycle");
            leavingCallback.leavingState(Created.INSTANCE);
            OperationResult result = operationCallback.doOperation(Created.INSTANCE);
            if ("operation".equals(failureStage) || "plugin".equals(failureStage)) {
                check(result == OperationResult.EXCEPTION, "Base callback must return EXCEPTION for gateway errors");
                Exception expectedException = "plugin".equals(failureStage) ? pluginFailure : failure;
                check(context.getException() == expectedException, "Original gateway exception must be stored");
            }
            State newState = switch (result) {
                case SUCCESS -> Succeeded.INSTANCE;
                case FAILURE -> Failed.INSTANCE;
                case EXCEPTION -> CapturePending.INSTANCE;
                case PENDING -> CapturePending.INSTANCE;
            };
            enteringCallback.enteringState(newState, operationCallback, result, leavingCallback);
            TransactionStatus expected = switch (result) {
                case SUCCESS -> TransactionStatus.SUCCESS;
                case FAILURE -> TransactionStatus.FAILURE;
                case PENDING -> TransactionStatus.UNKNOWN;
                case EXCEPTION -> TransactionStatus.UNKNOWN;
            };
            check(context.getPaymentTransaction().status() == expected, "Context must reflect persisted outcome");
            check(context.getPaymentTransaction().state() == TransactionState.forOutcome(type, expected),
                    "Only definitive outcomes may complete the lifecycle");
            if ("pending".equals(failureStage)) {
                check(context.getPaymentTransaction().gatewayStatus() == PaymentPluginStatus.PENDING,
                        "Provider pending status must survive UNKNOWN mapping");
            }
            check(context.getPaymentTransaction().processedAt().equals(java.time.Instant.parse("2026-09-29T12:00:00Z")),
                    "Context must use the persisted timestamp");
            return new PaymentResult(null, new OperationType(type.name()),
                    result == OperationResult.SUCCESS ? OperationState.SUCCESS : OperationState.UNKNOWN);
        };
        StateMachine machine = name -> {
            check(expectedState.equals(name), "Wrong state loaded: " + name);
            calls.add("state");
            return state;
        };
        StateMachineConfig config = name -> {
            check(expectedState.equals(name), "Wrong state used for machine lookup: " + name);
            calls.add("machine");
            return machine;
        };
        PaymentStateMachineHelper helper = new PaymentStateMachineHelper() {
            @Override
            public String getInitStateNameForTransaction(TransactionType actualType) {
                check(actualType == type, "Wrong transaction for initial-state lookup");
                calls.add("initial");
                return initialState;
            }

            @Override
            public StateMachineConfig getStateMachineConfig(String pluginName, InternalCallContext actualContext) {
                check("provider-plugin".equals(pluginName), "Wrong plugin name");
                check(actualContext == internalContext, "Wrong internal call context");
                calls.add("config");
                return config;
            }

            @Override
            public Operation getOperationForTransaction(StateMachineConfig actualConfig, TransactionType actualType) {
                check(actualConfig == config && actualType == type, "Wrong operation lookup arguments");
                calls.add("resolve-operation");
                return operation;
            }
        };

        try {
            new PaymentWorkflowRunner(helper, plugin).run(context, dao, currentState, type);
            check(!List.of("initiated", "database", "completed", "completed-database")
                    .contains(failureStage == null ? "" : failureStage), "Expected a persistence failure to propagate");
        } catch (PaymentAPIException exception) {
            if ("initiated".equals(failureStage) || "database".equals(failureStage)) {
                check(exception.getCause() instanceof OperationException,
                        "Leaving-state failure must be translated from OperationException");
                Throwable expectedCause = "database".equals(failureStage) ? databaseFailure : failure;
                check(exception.getCause().getCause() == expectedCause, "Original DAO failure must be preserved");
                check(exception.getCause().getMessage().equals("Unknown intent persistence failed"),
                        "Persistence failure must have an actionable message");
                check(context.getPaymentTransaction() == previousTransaction,
                        "Failed persistence must not replace the transaction in the context");
            } else {
                check("completed".equals(failureStage) || "completed-database".equals(failureStage), "Unexpected API failure");
                check(exception.getCause() instanceof OperationException, "Outcome failure must use OperationException");
                Throwable expectedCause = "completed-database".equals(failureStage) ? databaseFailure : failure;
                check(exception.getCause().getCause() == expectedCause, "Outcome persistence must preserve its cause");
                check(exception.getCause().getMessage().equals("Payment outcome persistence failed"), "Wrong outcome error message");
            }
        }

        List<String> expectedCalls = List.of("initial", "provider", "config", "machine", "state",
                "resolve-operation", "lifecycle", "initiated", "operation", "completed");
        if (failureStage != null) {
            String lastStage = switch (failureStage) {
                case "database" -> "initiated";
                case "initiated" -> "initiated";
                default -> "completed";
            };
            expectedCalls = expectedCalls.subList(0, expectedCalls.indexOf(lastStage) + 1);
        }
        check(calls.equals(expectedCalls), "Unexpected execution sequence: " + calls);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
