# Document 1: Lean PRD & Scope Lock (01-PRD.md)

## 1.1 Problem Statement
Modern engineering organizations integrating Large Language Models (LLMs) into production cannot allow internal microservices to call external providers (Gemini, OpenAI, Anthropic) directly. Direct integration creates four severe production failures:
1. **Uncontrolled token spend** with no real-time budget enforcement.
2. **Accidental leakage of PII, API keys, and credentials** into third-party prompts.
3. **Brittle availability** when a single upstream provider throttles (HTTP 429) or suffers an outage (HTTP 5xx).
4. **High latency and redundant cost** from repeatedly answering semantically identical queries.

**Orvix** solves this by acting as a centralized, zero-trust, high-throughput AI Gateway and FinOps control plane sitting between internal tenant applications and external LLM providers.

## 1.2 Target Personas
* **Tenant Application (API Consumer):** Internal microservices or external B2B clients authenticating via `X-API-Key` to execute LLM completions with strict latency, reliability, and budget guarantees.
* **Platform / FinOps Admin:** Engineers responsible for provisioning tenants, topping up prepaid credit wallets, configuring RPM/TPM rate limits, reviewing security audit logs, and monitoring system telemetry.

## 1.3 Core Functional Requirements (v1.0 Scope)
1. **Multi-Tenant Edge Routing & Rate Limiting:** Authenticate tenants via API key, enforce atomic per-tenant Requests-Per-Minute (RPM) and Tokens-Per-Minute (TPM) limits across horizontally scaled gateway nodes, and fail over automatically between primary and fallback LLM providers.
2. **Zero-Trust Prompt Guardrail Firewall:** Inspect every outbound prompt prior to provider dispatch. Automatically redact PII/secrets (emails, credit cards, API keys, SSN/Aadhaar patterns) and block known prompt-injection signatures. Log all violations immutably.
3. **Semantic Caching Layer:** Generate vector embeddings for sanitized prompts and perform cosine-similarity lookup against recent responses. Serve cache hits ($\ge 0.94$ similarity) directly without invoking upstream LLMs.
4. **Asynchronous FinOps Metering & Double-Entry Billing:** Emit immutable token-usage events to Apache Kafka upon request completion. Consume events idempotently, record balanced debit/credit entries in a PostgreSQL double-entry ledger, and enforce real-time quota cutoff via Redis when a tenant wallet reaches zero.
5. **End-to-End Observability & Proof of Scale:** Propagate W3C `traceparent` / `X-Trace-Id` across HTTP and Kafka boundaries, export Prometheus metrics and Jaeger traces, validate concurrency via Testcontainers, and publish k6 load-test benchmarks.

## 1.4 Explicit Non-Goals (Strict Scope Lock)
* **No Custom Frontend / React Admin Dashboard:** Administration is handled via REST APIs (OpenAPI/Swagger) and monitoring via pre-built Grafana dashboards.
* **No Real Payment Gateway Integration:** No Stripe/Razorpay checkout flows. Tenant wallets are funded via an authenticated Admin Credit API.
* **No Streaming SSE Token-by-Token Proxying in v1.0:** Standard synchronous JSON request/response completions only, ensuring exact token counts for billing and semantic caching.
* **No Custom ML Model Training:** Guardrails use deterministic regex/entropy rules + lightweight classifiers; embeddings use standard Spring AI embedding models.

## 1.5 Target Service Level Agreements (SLAs)
| Metric | Target SLA | Verification Method |
| :--- | :--- | :--- |
| **Gateway + Guardrail Overhead** | $< 25\text{ms}$ (p95) | k6 load test against mock LLM provider |
| **Semantic Cache Hit Latency** | $< 100\text{ms}$ (p95) | k6 load test with 40% repeated/paraphrased prompts |
| **Sustained Throughput** | $\ge 1,000\text{ RPS}$ (local Docker) | k6 stress test with Java 21 Virtual Threads enabled |
| **Billing Ledger Accuracy** | $0.00\%$ drift | Testcontainers concurrent burst + duplicate Kafka replay test |
| **Provider Failover Time** | $< 200\text{ms}$ after circuit trip | Chaos test killing Primary Mock LLM mid-load |
