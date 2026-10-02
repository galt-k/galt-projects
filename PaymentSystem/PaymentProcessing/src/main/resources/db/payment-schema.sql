CREATE TABLE payment_methods (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL,
    plugin_name VARCHAR(255) NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE payment_transactions (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL,
    payment_id UUID NOT NULL,
    transaction_type VARCHAR(32) NOT NULL CHECK (transaction_type IN ('PURCHASE', 'CHECKOUT', 'AUTHORIZE', 'CAPTURE')),
    amount NUMERIC(38, 18) NOT NULL,
    currency CHAR(3) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN'
        CHECK (status IN ('UNKNOWN', 'SUCCESS', 'FAILURE')),
    lifecycle_state VARCHAR(40) NOT NULL,
    gateway_status VARCHAR(16) CHECK (gateway_status IN ('PROCESSED', 'PENDING', 'ERROR', 'CANCELED')),
    CONSTRAINT transaction_lifecycle_check CHECK (
        lifecycle_state = (CASE transaction_type WHEN 'AUTHORIZE' THEN 'AUTHORIZATION'
            WHEN 'CAPTURE' THEN 'CAPTURE' ELSE 'CHECKOUT' END) ||
            (CASE WHEN status = 'UNKNOWN' THEN '_REQUESTED' ELSE '_COMPLETED' END)),
    CONSTRAINT transaction_pending_check CHECK (gateway_status <> 'PENDING' OR status = 'UNKNOWN'),
    gateway_transaction_id TEXT,
    gateway_error_code TEXT,
    gateway_error TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX payment_transactions_account_payment_idx ON payment_transactions (account_id, payment_id);
