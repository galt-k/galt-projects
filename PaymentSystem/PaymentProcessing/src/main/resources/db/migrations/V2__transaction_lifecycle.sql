BEGIN;

ALTER TABLE payment_transactions DROP CONSTRAINT payment_transactions_status_check;
ALTER TABLE payment_transactions ADD COLUMN lifecycle_state VARCHAR(40);
ALTER TABLE payment_transactions ADD COLUMN gateway_status VARCHAR(16);

UPDATE payment_transactions
SET gateway_status = CASE WHEN status = 'PENDING' THEN 'PENDING' ELSE NULL END,
    status = CASE status WHEN 'PAYMENT_FAILURE' THEN 'FAILURE' WHEN 'PENDING' THEN 'UNKNOWN' ELSE status END;

UPDATE payment_transactions
SET lifecycle_state = (CASE transaction_type WHEN 'AUTHORIZE' THEN 'AUTHORIZATION'
    WHEN 'CAPTURE' THEN 'CAPTURE' ELSE 'CHECKOUT' END) ||
    (CASE WHEN status = 'UNKNOWN' THEN '_REQUESTED' ELSE '_COMPLETED' END);

ALTER TABLE payment_transactions ALTER COLUMN lifecycle_state SET NOT NULL;
ALTER TABLE payment_transactions ADD CONSTRAINT payment_transactions_status_check
    CHECK (status IN ('UNKNOWN', 'SUCCESS', 'FAILURE'));
ALTER TABLE payment_transactions ADD CONSTRAINT transaction_gateway_status_check
    CHECK (gateway_status IN ('PROCESSED', 'PENDING', 'ERROR', 'CANCELED'));
ALTER TABLE payment_transactions ADD CONSTRAINT transaction_lifecycle_check CHECK (
    lifecycle_state = (CASE transaction_type WHEN 'AUTHORIZE' THEN 'AUTHORIZATION'
        WHEN 'CAPTURE' THEN 'CAPTURE' ELSE 'CHECKOUT' END) ||
        (CASE WHEN status = 'UNKNOWN' THEN '_REQUESTED' ELSE '_COMPLETED' END));
ALTER TABLE payment_transactions ADD CONSTRAINT transaction_pending_check
    CHECK (gateway_status <> 'PENDING' OR status = 'UNKNOWN');

COMMIT;
