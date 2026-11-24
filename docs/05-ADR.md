# Document 5: Architecture Decision Records (/docs/05-ADR.md)

## ADR-001: Atomic Redis Lua Scripts vs. Redisson Distributed Locks for Rate Limiting
* **Status:** Accepted | Component: `gateway-service`
* **Context:** Multiple horizontally scaled instances of `gateway-service` must enforce per-tenant RPM limits. A naive `GET` $\rightarrow$ `check` $\rightarrow$ `DECR` sequence suffers from race conditions under concurrent load, allowing tenants to burst past limits.
* **Decision:** Implement the Token Bucket algorithm inside a single atomic **Redis Lua script** executed via `EVALSHA`.
* **Trade-offs & Rationale:** Redisson distributed locks require multiple network round-trips (acquire lock, read, write, release lock) and introduce lock-contention latency under high RPS. Because Redis executes Lua scripts single-threaded and atomically, we achieve zero race conditions in **1 network round-trip (<1ms)**. Trade-off: Lua logic is harder to unit-test in isolation, mitigated by mandatory Testcontainers Redis integration tests.

---

## ADR-002: Double-Entry Ledger + Optimistic Locking vs. Mutable Balance Column
* **Status:** Accepted | Component: `billing-service`
* **Context:** Billing systems must survive concurrent deductions, duplicate message deliveries, and financial audits. A simple `UPDATE wallets SET balance = balance - cost` loses audit history and is vulnerable to lost updates or silent drift.
* **Decision:** Use an append-only **PostgreSQL Double-Entry Ledger** paired with a materialized `tenant_wallets` balance protected by JPA Optimistic Locking (`@Version`) and integer micro-dollars.
* **Trade-offs & Rationale:** Every balance change is mathematically provable by summing `ledger_entries`. Optimistic locking avoids heavy database row-lock contention (pessimistic `SELECT FOR UPDATE`) while guaranteeing zero lost updates via automatic retry on version conflict. Trade-off: Slightly higher write amplification (2 ledger rows + 1 wallet update per event), which is negligible at our async Kafka ingestion rate.

---

## ADR-003: Asynchronous Kafka Metering vs. Synchronous REST Billing
* **Status:** Accepted | Component: `gateway-service` $\rightarrow$ `billing-service`
* **Context:** Calling `billing-service` synchronously on every LLM request couples user-facing latency and availability directly to the billing database.
* **Decision:** Decouple metering via **Apache Kafka** (`orvix.usage.recorded`) with manual offset commits, idempotent event tracking (`processed_events`), and a Dead Letter Queue (`orvix.usage.dlq`).
* **Trade-offs & Rationale:** Eliminates billing DB latency from the hot path and absorbs massive traffic spikes without dropping requests. Trade-off: Introduces eventual consistency (a tenant could theoretically exceed their $0 balance by a few milliseconds of in-flight requests before the Redis block flag propagates). This micro-overage is an accepted industry standard (used by Stripe, AWS, and OpenAI) in exchange for sub-25ms gateway overhead.

---

## ADR-004: PostgreSQL pgvector (HNSW) Semantic Caching vs. Exact-Match Redis Cache
* **Status:** Accepted | Component: `cache-service`
* **Context:** Exact-match string caching yields near-zero hit rates for natural language prompts (e.g., "What is CAP theorem?" vs. "Explain the CAP theorem").
* **Decision:** Use **PostgreSQL with the pgvector extension** and an **HNSW** (Hierarchical Navigable Small World) cosine-similarity index (threshold $\ge 0.94$).
* **Trade-offs & Rationale:** Avoids introducing a separate dedicated vector database (Pinecone/Milvus) into the infrastructure footprint while delivering sub-30ms approximate nearest-neighbor search. Trade-off: Embedding generation adds ~15-30ms on cache misses, so cache lookup is strictly bounded by a 100ms fail-open circuit breaker.

---

## ADR-005: Java 21 Virtual Threads vs. Spring WebFlux (Reactive)
* **Status:** Accepted | Component: All Microservices
* **Context:** An AI Gateway is overwhelmingly I/O-bound (waiting 500ms-3000ms on upstream LLM HTTP calls). Traditional platform thread pools (200 threads) exhaust quickly under high concurrency.
* **Decision:** Use standard imperative **Spring MVC / Spring Cloud Gateway MVC with Java 21 Virtual Threads** enabled (`spring.threads.virtual.enabled=true`).
* **Trade-offs & Rationale:** Virtual Threads deliver WebFlux-level concurrency for blocking I/O without the severe debugging, stack-trace, and Spring Data JPA incompatibility overhead of Reactive programming. It also keeps JDBC/PostgreSQL transactions clean and predictable.
