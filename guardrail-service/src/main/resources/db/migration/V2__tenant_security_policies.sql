-- V2__tenant_security_policies.sql

CREATE TABLE IF NOT EXISTS tenant_security_policies (
    tenant_id VARCHAR(36) PRIMARY KEY,
    risk_threshold DOUBLE PRECISION NOT NULL DEFAULT 0.60,
    strict_mode BOOLEAN NOT NULL DEFAULT FALSE,
    block_obfuscation BOOLEAN NOT NULL DEFAULT TRUE,
    strict_pii_scrubbing BOOLEAN NOT NULL DEFAULT TRUE,
    custom_blocked_keywords TEXT[] DEFAULT '{}',
    allowed_keywords TEXT[] DEFAULT '{}',
    updated_at TIMESTAMPTZ NOT NULL
);
