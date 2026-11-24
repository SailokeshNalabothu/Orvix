# Document 2: High-Level Design / RFC (02-HLD.md)

## 2.1 Microservices Boundaries & Strict Data Ownership
Orvix enforces strict **database-per-service isolation**. No service may query another service's database tables directly; all cross-service communication occurs over versioned REST APIs or Kafka topics.

| Service | Port | Owned Data Store | Core Responsibility |
| :--- | :--- | :--- | :--- |
| `gateway-service` | 8080 | Redis (Rate Limit buckets, Quota block flags, API key cache) | Edge ingress, API key auth, atomic Redis Lua rate limiting, orchestration of Guardrail $\rightarrow$ Cache $\rightarrow$ LLM Router (Resilience4j), Kafka usage emission. |
| `guardrail-service` | 8081 | PostgreSQL (`guardrail_db`) | Zero-trust prompt inspection, PII/secret redaction, prompt-injection blocking, immutable security audit persistence. |
| `cache-service` | 8082 | PostgreSQL + pgvector (`cache_db`) | Prompt embedding generation via Spring AI, HNSW cosine-similarity lookup, TTL-based semantic cache storage. |
| `billing-service` | 8083 | PostgreSQL (`billing_db`) | Tenant/wallet management, idempotent Kafka usage consumption, double-entry ledger accounting, real-time quota exhaustion publishing. |

## 2.2 End-to-End Request & Event Flows

### A. Synchronous Request Path (Hot Path)
`Client` --[`POST /v1/chat/completions` + `X-API-Key` + `X-Idempotency-Key` requirement]--> `gateway-service`
1. **Auth & Quota Check:** Validate API Key & check Redis `quota:blocked:{tenantId}` (fail fast: HTTP 402).
2. **Rate Limit:** Execute atomic Redis Lua Token-Bucket script (fail fast: HTTP 429).
3. **Guardrail Call (Sync HTTP):** `POST guardrail-service/internal/v1/inspect`
   - If `BLOCKED` (injection) $\rightarrow$ Return HTTP 400 + log audit event.
   - If `REDACTED` (PII found) $\rightarrow$ Proceed with sanitized prompt.
4. **Semantic Cache Lookup (Sync HTTP):** `POST cache-service/internal/v1/lookup`
   - If `HIT` (similarity $\ge 0.94$) $\rightarrow$ Return cached response immediately ($0$ LLM tokens billed; cache-hit fee applied).
5. **LLM Provider Dispatch (Resilience4j CircuitBreaker + Retry):**
   - Primary Provider (Gemini / Mock Primary) $\rightarrow$ On 429/5xx/Timeout $\rightarrow$ Failover to Secondary Provider.
6. **Async Side Effects (Non-blocking):**
   - Publish `UsageRecordedEvent` to Kafka topic `orvix.usage.recorded`.
   - Fire-and-forget cache write to `cache-service/internal/v1/store`.
7. **Return standardized JSON response** to Client with `X-Trace-Id`, `X-Cache-Status`, and `X-Provider-Used`.

### B. Asynchronous Billing & Real-Time Quota Cutoff Path
`Kafka` (`orvix.usage.recorded`) $\rightarrow$ `billing-service` (`@KafkaListener`, manual ack)
1. **Idempotency Check:** Insert `event_id` into `processed_events` (PK constraint). If duplicate $\rightarrow$ Ack & skip.
2. **Cost Calculation:** Compute exact cost in micro-dollars ($1\text{ USD} = 1,000,000\text{ micro-dollars}$) to avoid floating-point drift.
3. **Double-Entry Ledger Transaction** (PostgreSQL Serializable / Optimistic Lock `@Version`):
   - Insert `DEBIT` entry on Tenant Wallet Account.
   - Insert `CREDIT` entry on Platform Revenue Account.
   - Update `tenant_wallets.balance_micros` with `WHERE version = ?`.
4. **Quota Enforcement:** If `balance_micros <= 0`:
   - Publish `QuotaExhaustedEvent` to Kafka (`orvix.quota.exhausted`).
   - `gateway-service` consumes event and sets Redis key `quota:blocked:{tenantId} = 1`.

## 2.3 Failure-Mode Matrix ("What Breaks?")
| Failure Scenario | Policy | Exact System Behavior |
| :--- | :--- | :--- |
| **Redis Unreachable** | Fail-Closed (Configurable) | Gateway cannot verify quota block or rate limits. Returns HTTP 503 Service Unavailable with `Retry-After: 5` to prevent unthrottled LLM spend. |
| **Guardrail Service Down / Timeout (>150ms)** | Fail-Closed | Zero-trust security mandate: never forward uninspected prompts to external LLMs. Returns HTTP 503 and increments `guardrail_fail_closed_total` metric. |
| **Semantic Cache Service Down / Timeout (>100ms)** | Fail-Open | Cache is a performance optimization, not a hard dependency. Circuit breaker opens, logs warning, and routes directly to LLM provider. |
| **Primary LLM Returns 429 / 5xx / Timeout** | Automatic Failover | Resilience4j trips circuit breaker (50% failure rate over 10-call sliding window) and routes immediately to Secondary Provider. |
| **Kafka Broker Down During Request** | Fallback Buffer | Gateway logs structured fallback usage record to local recovery table / dead-letter file for reconciliation; client response is not failed. |
| **Billing Consumer Poison Pill / DB Error** | Retry + DLQ | Spring Kafka retries 3 times with exponential backoff (1s, 2s, 4s). On exhaustion, publishes to `orvix.usage.dlq` for manual replay. |
| **Concurrent Wallet Updates (Race Condition)** | Optimistic Lock Retry | `OptimisticLockingFailureException` triggers automatic `@Retryable` (max 5 attempts, 20ms jitter) before committing. |

## 2.4 Technology Stack Lock
* **Runtime & Framework:** Java 21 (Virtual Threads enabled via `spring.threads.virtual.enabled=true`), Spring Boot 3.3+, Spring Cloud Gateway MVC, Spring AI, Resilience4j.
* **Messaging & Storage:** Apache Kafka (KRaft mode, zero ZooKeeper), Redis 7.x (Lua scripting), PostgreSQL 16 + `pgvector` extension.
* **Observability & Testing:** Micrometer + OpenTelemetry, Prometheus, Grafana, Jaeger, JUnit 5, Testcontainers, k6, GitHub Actions CI.
