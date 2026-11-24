# Document 3: API & Event Contracts (03-CONTRACTS.md)

## 3.1 Standard Request & Response Headers
| Header | Direction | Contract & Validation Rules |
| :--- | :--- | :--- |
| `X-API-Key` | Request (Req) | Tenant authentication key (e.g., `orv_live_9f8a7b...`). Hashed via SHA-256 before lookup. |
| `X-Idempotency-Key` | Request (Opt) | UUIDv4 supplied by client. If omitted, Gateway generates a unique requestId for downstream billing idempotency. |
| `X-Trace-Id` | Response | 32-character hex OpenTelemetry Trace ID propagated across all 4 services and Kafka headers. |
| `X-Cache-Status` | Response | Enum: `HIT`, `MISS`, or `BYPASS` (when cache service is degraded). |
| `X-Provider-Used` | Response | Identifies the upstream provider that served the response (e.g., `gemini-primary`, `openai-fallback`, `semantic-cache`). |

## 3.2 Public Gateway Endpoint
`POST /v1/chat/completions`

### Request Payload:
```json
{
  "model": "gpt-4o-mini",
  "messages": [
    { "role": "user", "content": "Explain idempotency in distributed billing systems." }
  ],
  "temperature": 0.2,
  "max_tokens": 512,
  "cache_enabled": true
}
```

### Success Response (HTTP 200 OK):
```json
{
  "id": "req_01J9X8K2M4P5Q6R7S8T9",
  "provider": "gemini-primary",
  "model": "gpt-4o-mini",
  "content": "Idempotency ensures that processing the same billing event multiple times...",
  "guardrail": {
    "status": "CLEAN",
    "redactions_count": 0
  },
  "usage": {
    "prompt_tokens": 14,
    "completion_tokens": 112,
    "total_tokens": 126,
    "estimated_cost_micros": 189
  },
  "latency_ms": 42
}
```

## 3.3 Standard Error Envelope (RFC 7807 Problem Details)
All 4xx/5xx responses across every service strictly conform to RFC 7807:
```json
{
  "type": "https://orvix.internal/errors/rate-limit-exceeded",
  "title": "Rate Limit Exceeded",
  "status": 429,
  "detail": "Tenant exceeded 60 RPM limit. Retry after 12 seconds.",
  "instance": "/v1/chat/completions",
  "trace_id": "4bf92f3577b34da6a3ce929d0e0e4736",
  "timestamp": "2026-11-24T12:00:00Z"
}
```
**Locked HTTP Error Codes:**
* `400`: Prompt Injection Blocked / Invalid Payload
* `401`: Invalid API Key
* `402`: Insufficient Wallet Balance / Quota Exhausted
* `429`: RPM/TPM Rate Limit Exceeded
* `502`: All Upstream LLM Providers Failed
* `503`: Fail-Closed Dependency Down

## 3.4 Admin FinOps Endpoints (billing-service)
* `POST /admin/v1/tenants` — Creates a new tenant, generates an API key, provisions a wallet with initial balance, and sets RPM/TPM limits.
* `POST /admin/v1/tenants/{tenantId}/credits` — Atomically credits a tenant wallet (double-entry CREDIT to wallet, DEBIT to funding source) and clears any Redis `quota:blocked` flag.
* `GET /admin/v1/tenants/{tenantId}/ledger` — Returns paginated double-entry ledger history and current verified balance.

## 3.5 Kafka Event Contracts
| Topic Name | Partition Key | Producer $\rightarrow$ Consumer | Purpose & Retention |
| :--- | :--- | :--- | :--- |
| `orvix.usage.recorded` | `tenantId` | `gateway` $\rightarrow$ `billing` | Primary metering stream. Partitioned by `tenantId` to preserve per-tenant ordering. Retention: 7 days. |
| `orvix.usage.dlq` | `tenantId` | `billing` $\rightarrow$ Ops Replay | Captures unprocessable usage events after 3 retries. Retention: 30 days. |
| `orvix.quota.exhausted` | `tenantId` | `billing` $\rightarrow$ `gateway` | Triggers immediate Redis block flag when wallet balance $\le 0$. |

### Schema: `orvix.usage.recorded` (JSON Payload):
```json
{
  "event_id": "evt_01J9X8K2M4P5Q6R7S8T9",
  "trace_id": "4bf92f3577b34da6a3ce929d0e0e4736",
  "tenant_id": "ten_acme_corp",
  "request_id": "req_01J9X8K2M4P5Q6R7S8T9",
  "provider": "gemini-primary",
  "model": "gpt-4o-mini",
  "cache_hit": false,
  "prompt_tokens": 14,
  "completion_tokens": 112,
  "occurred_at": "2026-11-24T12:00:00.123Z"
}
```
