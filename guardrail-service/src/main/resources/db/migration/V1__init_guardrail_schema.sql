-- V1__init_guardrail_schema.sql

CREATE TABLE IF NOT EXISTS security_audit_logs (
    id BIGSERIAL PRIMARY KEY,
    trace_id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(36) NOT NULL,
    action VARCHAR(16) NOT NULL CHECK (action IN ('REDACTED', 'BLOCKED')),
    violation_types TEXT[] NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_audit_tenant_created ON security_audit_logs(tenant_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_trace ON security_audit_logs(trace_id);
