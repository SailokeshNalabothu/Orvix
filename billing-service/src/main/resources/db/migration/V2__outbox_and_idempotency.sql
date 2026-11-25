-- V2__outbox_and_idempotency.sql

-- 1. Transactional Outbox Table
CREATE TABLE IF NOT EXISTS outbox_events (
    id VARCHAR(64) PRIMARY KEY,
    aggregate_type VARCHAR(32) NOT NULL,
    aggregate_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload TEXT NOT NULL,
    destination_topic VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED')),
    retry_count INT NOT NULL DEFAULT 0,
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_outbox_pending ON outbox_events(status, created_at ASC);

-- 2. Refund Idempotency Table
CREATE TABLE IF NOT EXISTS refund_idempotency (
    idempotency_key VARCHAR(128) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    reference_transaction_id VARCHAR(64) NOT NULL,
    refund_transaction_id VARCHAR(64) NOT NULL,
    amount_micros BIGINT NOT NULL,
    previous_balance BIGINT NOT NULL,
    new_balance BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_refund_idem_tenant ON refund_idempotency(tenant_id, created_at DESC);
