# Document 4: Database & Storage Schema (04-DB-SCHEMA.md)

## 4.1 Billing Database (PostgreSQL: billing_db)
All monetary values are stored as **64-bit integers in micro-dollars** (`BIGINT`, where `1,000,000 = $1.00 USD`) to eliminate IEEE-754 floating-point rounding drift.

### Table: `tenants`
```sql
CREATE TABLE tenants (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    api_key_hash CHAR(64) UNIQUE NOT NULL,
    rpm_limit INT NOT NULL,
    tpm_limit INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);
CREATE UNIQUE INDEX idx_tenants_api_key_hash ON tenants(api_key_hash);
```

### Table: `tenant_wallets`
```sql
CREATE TABLE tenant_wallets (
    tenant_id VARCHAR(36) PRIMARY KEY REFERENCES tenants(id),
    balance_micros BIGINT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL
);
-- Optimistic locking via JPA @Version on version column
```

### Table: `ledger_entries`
```sql
CREATE TABLE ledger_entries (
    id BIGSERIAL PRIMARY KEY,
    transaction_id VARCHAR(36) NOT NULL,
    tenant_id VARCHAR(36) NOT NULL,
    account_type VARCHAR(24) NOT NULL CHECK (account_type IN ('TENANT_WALLET', 'PLATFORM_REVENUE', 'FUNDING_SOURCE')),
    entry_type VARCHAR(8) NOT NULL CHECK (entry_type IN ('DEBIT', 'CREDIT')),
    amount_micros BIGINT NOT NULL CHECK (amount_micros > 0),
    reference_event_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_ledger_tenant_created ON ledger_entries(tenant_id, created_at DESC);
CREATE INDEX idx_ledger_transaction ON ledger_entries(transaction_id);
```

### Table: `processed_events`
```sql
CREATE TABLE processed_events (
    event_id VARCHAR(64) PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL
);
-- PK enforces strict Kafka consumer idempotency
```

**Double-Entry Invariant:** For every `transaction_id` in `ledger_entries`, `SUM(DEBIT) == SUM(CREDIT)`. Entries are strictly append-only; corrections are recorded as reversing entries, never `UPDATE` or `DELETE`.

---

## 4.2 Semantic Cache Database (PostgreSQL + pgvector: cache_db)

### Extension:
```sql
CREATE EXTENSION IF NOT EXISTS vector;
```

### Table: `semantic_cache`
```sql
CREATE TABLE semantic_cache (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    model VARCHAR(64) NOT NULL,
    prompt_hash CHAR(64) NOT NULL,
    prompt_text TEXT NOT NULL,
    embedding VECTOR(768) NOT NULL,
    response_text TEXT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_semantic_cache_embedding ON semantic_cache USING hnsw (embedding vector_cosine_ops);
CREATE INDEX idx_semantic_cache_lookup ON semantic_cache(tenant_id, model, expires_at);
```

---

## 4.3 Guardrail Audit Database (PostgreSQL: guardrail_db)

### Table: `security_audit_logs`
```sql
CREATE TABLE security_audit_logs (
    id BIGSERIAL PRIMARY KEY,
    trace_id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(36) NOT NULL,
    action VARCHAR(16) NOT NULL CHECK (action IN ('REDACTED', 'BLOCKED')),
    violation_types TEXT[] NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_audit_tenant_created ON security_audit_logs(tenant_id, created_at DESC);
CREATE INDEX idx_audit_trace ON security_audit_logs(trace_id);
```

---

## 4.4 Redis Key-Space Design (gateway-service)
| Key Pattern | Type / TTL | Purpose |
| :--- | :--- | :--- |
| `rl:rpm:{tenantId}` | Hash / 120s | Stores `tokens` and `last_refill_ms` for atomic Lua token-bucket RPM enforcement. |
| `quota:blocked:{tenantId}` | String / No TTL | Set to `"1"` when wallet hits 0; deleted immediately upon Admin Credit top-up. |
| `auth:apikey:{sha256}` | String (JSON) / 300s | Caches tenant metadata (`tenantId`, `rpmLimit`, `status`) to avoid DB round-trips on every request. |
