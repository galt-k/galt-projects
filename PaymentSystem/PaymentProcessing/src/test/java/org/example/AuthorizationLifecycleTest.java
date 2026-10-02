package org.example;

import org.example.callbacks.AuthorizeCompleted;
import org.example.callbacks.AuthorizeInitiated;
import org.example.persistence.PaymentWorkflowDAOHelper;
import org.example.plugin.PaymentPluginAPIException;
import org.example.plugin.PaymentPluginStatus;
import org.example.states.Created;
import org.example.workflow.OperationCallback;
import org.example.workflow.OperationResult;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AuthorizationLifecycleTest {
    @ParameterizedTest
    @CsvSource({"PROCESSED,SUCCESS,AUTHORIZATION_COMPLETED", "ERROR,FAILURE,AUTHORIZATION_COMPLETED",
            "CANCELED,FAILURE,AUTHORIZATION_COMPLETED", "PENDING,UNKNOWN,AUTHORIZATION_REQUESTED",
            "TIMEOUT,UNKNOWN,AUTHORIZATION_REQUESTED"})
    void intentAndOutcomeArePersistedThroughExistingCallbacks(String providerOutcome, TransactionStatus expected,
                                                            TransactionState expectedState) throws Exception {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("db/payment-schema.sql")).execute(source);
        JdbcTemplate jdbc = new JdbcTemplate(source);
        var context = PaymentTestContexts.create(new InternalCallContext() {}, TransactionType.AUTHORIZE);
        var helper = new PaymentWorkflowDAOHelper(source);
        var leaving = new AuthorizeInitiated(helper, context);
        var entering = new AuthorizeCompleted(helper, context);

        OperationCallback operation = state -> {
            assertEquals("AUTHORIZATION_REQUESTED", jdbc.queryForObject(
                    "SELECT lifecycle_state FROM payment_transactions WHERE id = ?", String.class, context.getTransactionId()));
            assertEquals("UNKNOWN", jdbc.queryForObject(
                    "SELECT status FROM payment_transactions WHERE id = ?", String.class, context.getTransactionId()));
            if (providerOutcome.equals("TIMEOUT")) {
                context.setException(new PaymentPluginAPIException("Timeout"));
                return OperationResult.EXCEPTION;
            }
            PaymentPluginStatus providerStatus = PaymentPluginStatus.valueOf(providerOutcome);
            context.setGatewayStatus(providerStatus);
            return switch (providerStatus) {
                case PROCESSED -> OperationResult.SUCCESS;
                case ERROR, CANCELED -> OperationResult.FAILURE;
                case PENDING -> OperationResult.PENDING;
            };
        };
        leaving.leavingState(Created.INSTANCE);
        assertEquals(TransactionState.AUTHORIZATION_REQUESTED, context.getPaymentTransaction().state());
        OperationResult result = operation.doOperation(Created.INSTANCE);
        entering.enteringState(Created.INSTANCE, operation, result, leaving);

        assertEquals(expectedState, context.getPaymentTransaction().state());
        assertEquals(expected, context.getPaymentTransaction().status());
        var row = jdbc.queryForMap("SELECT status, lifecycle_state, gateway_status FROM payment_transactions WHERE id = ?",
                context.getTransactionId());
        assertEquals(expected.name(), row.get("status"));
        assertEquals(expectedState.name(), row.get("lifecycle_state"));
        assertEquals(providerOutcome.equals("TIMEOUT") ? null : providerOutcome, row.get("gateway_status"));
        assertEquals(context.getGatewayStatus(), context.getPaymentTransaction().gatewayStatus());
    }
}
