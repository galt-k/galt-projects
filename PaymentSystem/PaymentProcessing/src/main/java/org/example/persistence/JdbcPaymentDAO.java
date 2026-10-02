package org.example.persistence;

import org.example.TransactionStatus;
import org.example.TransactionType;
import org.example.TransactionState;
import org.example.plugin.PaymentPluginStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;

public final class JdbcPaymentDAO implements PaymentDAO {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcPaymentDAO(DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(Objects.requireNonNull(dataSource, "dataSource"));
    }

    @Override
    public PaymentTransactionModel insertPaymentTransaction(PaymentTransactionModel transaction) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("id", transaction.id())
                .addValue("accountId", transaction.accountId())
                .addValue("paymentId", transaction.paymentId())
                .addValue("transactionType", transaction.transactionType().name())
                .addValue("amount", transaction.amount())
                .addValue("currency", transaction.currency().getCurrencyCode())
                .addValue("status", transaction.status().name())
                .addValue("state", transaction.state().name())
                .addValue("gatewayStatus", transaction.gatewayStatus() == null ? null : transaction.gatewayStatus().name())
                .addValue("createdAt", transaction.createdAt().atOffset(ZoneOffset.UTC));
        jdbc.update("""
                INSERT INTO payment_transactions
                    (id, account_id, payment_id, transaction_type, amount, currency, status, lifecycle_state, gateway_status, created_at)
                VALUES (:id, :accountId, :paymentId, :transactionType, :amount, :currency, :status, :state, :gatewayStatus, :createdAt)
                """, parameters);
        return jdbc.queryForObject("""
                SELECT id, account_id, payment_id, transaction_type, amount, currency, status,
                       gateway_transaction_id, gateway_error_code, gateway_error, created_at, processed_at, lifecycle_state, gateway_status
                FROM payment_transactions
                WHERE id = :id AND account_id = :accountId AND payment_id = :paymentId
                """, parameters, (result, row) -> {
            OffsetDateTime processedAt = result.getObject("processed_at", OffsetDateTime.class);
            return new PaymentTransactionModel(
                    result.getObject("id", UUID.class), result.getObject("account_id", UUID.class),
                    result.getObject("payment_id", UUID.class), TransactionType.valueOf(result.getString("transaction_type")),
                    result.getBigDecimal("amount"), Currency.getInstance(result.getString("currency")),
                    TransactionStatus.valueOf(result.getString("status")), result.getString("gateway_transaction_id"),
                    result.getString("gateway_error_code"), result.getString("gateway_error"),
                    result.getObject("created_at", OffsetDateTime.class).toInstant(),
                    processedAt == null ? null : processedAt.toInstant(),
                    TransactionState.valueOf(result.getString("lifecycle_state")),
                    result.getString("gateway_status") == null ? null : PaymentPluginStatus.valueOf(result.getString("gateway_status")));
        });
    }

    @Override
    public int updatePaymentTransactionStatus(UUID accountId, UUID paymentId, UUID transactionId,
            TransactionStatus status, String gatewayTransactionId, String gatewayErrorCode,
            String gatewayError, Instant processedAt, org.example.plugin.PaymentPluginStatus gatewayStatus) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("accountId", accountId).addValue("paymentId", paymentId).addValue("id", transactionId)
                .addValue("gatewayStatus", gatewayStatus == null ? null : gatewayStatus.name())
                .addValue("status", status.name()).addValue("gatewayTransactionId", gatewayTransactionId)
                .addValue("gatewayErrorCode", gatewayErrorCode).addValue("gatewayError", gatewayError)
                .addValue("processedAt", processedAt.atOffset(ZoneOffset.UTC));
        return jdbc.update("""
                UPDATE payment_transactions
                SET status = :status,
                    lifecycle_state = (CASE transaction_type
                        WHEN 'AUTHORIZE' THEN 'AUTHORIZATION'
                        WHEN 'CAPTURE' THEN 'CAPTURE'
                        ELSE 'CHECKOUT' END) ||
                        (CASE WHEN :status = 'UNKNOWN' THEN '_REQUESTED' ELSE '_COMPLETED' END),
                    gateway_status = :gatewayStatus,
                    gateway_transaction_id = COALESCE(:gatewayTransactionId, gateway_transaction_id),
                    gateway_error_code = :gatewayErrorCode,
                    gateway_error = :gatewayError,
                    processed_at = :processedAt
                WHERE id = :id AND account_id = :accountId AND payment_id = :paymentId
                """, parameters);
    }

    @Override
    public String getPaymentProviderPluginName(UUID accountId, UUID paymentMethodId, boolean includeDeletedPaymentMethod) {
        return jdbc.queryForObject("""
                SELECT plugin_name FROM payment_methods
                WHERE account_id = :accountId AND id = :paymentMethodId
                  AND (:includeDeleted OR deleted = FALSE)
                """, new MapSqlParameterSource().addValue("accountId", accountId)
                .addValue("paymentMethodId", paymentMethodId).addValue("includeDeleted", includeDeletedPaymentMethod), String.class);
    }
}
