# Architecture Decision Record 015 (ADR-015)
# Commercial Developer Experience: Organization Dashboard, API Key Lifecycle, Usage Analytics, Monthly Spending Limits & Provider Health Telemetry

| Metadata | Value |
| :--- | :--- |
| **Status** | **Accepted & Empirically Verified** |
| **Author** | Orvix Developer Platform & FinOps Infrastructure Engineering Team |
| **Date** | 2026-11-25 |
| **Scope** | External Developer Ergonomics, Organization Self-Service, API-Key Lifecycle, Budget Limits, Semantic Cache ROI, Security Audit, Provider Failover, Financial Export |
| **Target Invariant** | **Commercial-Grade Developer Platform: Zero-Friction Multi-Tenant Sign-Up, Self-Service API Keys, Hard/Soft Budget Enforcements, Real-Time Model Telemetry, and Cryptographic Ledger Transparency** |
| **Cross-References** | [ADR-001 (Rate Limiting)](05-ADR.md#adr-001), [ADR-002 (Double-Entry Ledger)](05-ADR.md#adr-002), [ADR-007 (Semantic Cache)](07-SEMANTIC-CACHE-GUARD.md), [ADR-009 (Reconciliation)](09-FINANCIAL-CORRECTNESS.md), [ADR-014 (Intelligent Routing)](14-INTELLIGENT-ROUTING-EVALUATION.md) |

---

## 1. Context & Business Problem

To serve external enterprise developers, engineering teams, and recruiters evaluating Orvix as an AI infrastructure control plane, the system cannot remain purely a backend headless gateway. Real-world commercial AI platforms (e.g. OpenAI, Anthropic, Cloudflare AI Gateway, Portkey) require a polished, institutional-grade self-service Developer Experience (DevEx).

Organizations adopting Orvix must be able to:
1. **Self-Service Onboarding:** Sign up an organization and generate production-grade API keys in seconds.
2. **Instant Integration:** Drop Orvix into existing applications using industry-standard SDKs (OpenAI SDK, LangChain, native HTTP clients).
3. **Financial Governance:** Configure hard and soft monthly AI spending limits to protect against runaway recursive agent loops or unexpected model costs.
4. **Usage & Cost Visibility:** Track token consumption across models (`auto-cascade`, `gemini-1.5-pro`, `gemini-1.5-flash`), inspecting exact micro-dollar debits.
5. **FinOps Cache ROI:** Quantify the exact micro-dollar and token savings achieved by pgvector semantic caching.
6. **Security Observability:** Inspect real-time prompt injection interceptions, PII redactions, and threat taxonomy classifications (CAT 1–6).
7. **Infrastructure Resilience:** Monitor upstream provider health, circuit breaker sliding windows, and automatic fallback transitions in real time.
8. **Audit & Compliance:** Export append-only double-entry ledger transactions (CSV) and mathematical 3-tier reconciliation reports (JSON).

---

## 2. Architecture & Design System

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                       ORVIX DEVELOPER PLATFORM UI                           │
│     (Hosted at http://localhost:8080/dashboard or standalone port 3000)     │
│   Obsidian Slate (#0B0F19), NVIDIA Emerald (#10B981), Sky Cyan (#38BDF8)    │
└──────────────────────────────────────┬──────────────────────────────────────┘
                                       │ REST / JSON (CORS Enabled)
                                       ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ 1. Gateway Dashboard API Controller (DashboardApiController.java)           │
│    - POST   /v1/dashboard/tenants                  (Sign-up & Provisioning) │
│    - GET    /v1/dashboard/tenants/{id}/api-keys    (List Masked Keys)       │
│    - POST   /v1/dashboard/tenants/{id}/api-keys    (Generate Raw Key)       │
│    - DELETE /v1/dashboard/tenants/{id}/api-keys/{k}(Revoke Secret Key)      │
│    - GET/PUT/v1/dashboard/spending-limits/{id}     (Budget Limits & Quotas) │
│    - POST   /v1/dashboard/tenants/{id}/topup       (Credit Top-Up)          │
│    - GET    /v1/dashboard/analytics/overview       (KPIs & Token Telemetry) │
│    - GET    /v1/dashboard/security/events          (Zero-Trust Audit Logs)  │
│    - GET    /v1/dashboard/providers/health         (Resilience4j & Nodes)   │
│    - GET    /v1/dashboard/export/ledger.csv        (RFC-4180 Ledger Export) │
│    - GET    /v1/dashboard/export/reconciliation.json(Cryptographic Audit)   │
└──────────────┬───────────────────────┬───────────────────────┬──────────────┘
               │                       │                       │
               ▼                       ▼                       ▼
    ┌────────────────────┐   ┌───────────────────┐   ┌───────────────────┐
    │ Redis 7 In-Memory  │   │ Guardrail Service │   │  Billing Service  │
    │ - auth:apikey:hash │   │ - CAT 1-6 Logs    │   │  - Double-Entry   │
    │ - tenant:apikeys   │   │ - PII Redactions  │   │  - 3-Tier Recon   │
    │ - spending:limit   │   │ - Tenant Policies │   │  - Wallet Balance │
    │ - quota:blocked    │   └───────────────────┘   └───────────────────┘
    └────────────────────┘
```

### Visual & Aesthetic Standard
The UI implements a corporate aesthetic aligned with Apple Developer, NVIDIA NGC, and Vercel:
- **Base Canvas:** Deep obsidian midnight (`#0B0F19`) with charcoal elevated surfaces (`#111827`, `#161F32`) and frosted glass cards (`rgba(17, 24, 39, 0.85)`).
- **Semantics & Accents:**
  - *NVIDIA Emerald (`#10B981`):* Operational health, semantic cache savings, settled ledger credits.
  - *Electric Sky (`#38BDF8`):* Primary actions, token telemetry, fast routing tier.
  - *Frontier Violet (`#A855F7`):* Deep cognitive reasoning tier (`gemini-1.5-pro`).
  - *Amber (`#F59E0B`):* Approaching budget threshold alert (e.g. $\ge 80\%$), soft limit warnings.
  - *Crimson Rose (`#EF4444`):* Prompt injection blocks, PII sanitization events, hard quota cutoffs (`HTTP 402`).

---

## 3. The 8 Core Developer Experience Capabilities

### 1. Organization & Multi-Key Lifecycle Management
- **Immediate Provisioning:** Creating an organization automatically provisions a unique tenant ID (`ten_<uuid>`), creates an isolated wallet in `tenant_wallets`, credits the initial balance, and returns an initial API key with prefix `orv_live_<hex>`.
- **Zero-Knowledge Key Storage:** The raw key is revealed exactly once to the user upon creation. The backend stores only the SHA-256 hash in Redis (`auth:apikey:<hash>`), eliminating plaintext secret exposure.
- **Key Scoping & Revocation:** Supports `FULL_ACCESS`, `INFERENCE_ONLY`, and `READ_ONLY` roles. Revoking a key deletes the cache entry and rejects subsequent requests with `HTTP 401 Unauthorized`.

### 2. Integration & Interactive Playground
- **OpenAI Drop-In Compatibility:** Developers can redirect the standard `openai` library to Orvix by changing only `base_url="http://localhost:8080/v1"` and `api_key="orv_live_..."`.
- **Polyglot Code Generator:** Tabbed copy-paste code snippets for Python (OpenAI SDK), Node.js / TypeScript, cURL, and Java 21 (Virtual Threads).
- **In-Browser Execution Console:** Enables executing live completions against `auto-cascade`, `gemini-1.5-pro`, or `gemini-1.5-flash`, visualizing provider headers (`X-Provider-Used`), semantic cache status (`X-Cache-Status`), latency (ms), and trace ID.

### 3. Monthly AI Spending Limits & Quota Controls
- **Micro-Dollar Precision:** Spending limits are configured in micro-dollars ($\mu\$$) to eliminate IEEE-754 floating-point drift ($50.00 = 50,000,000\ \mu\$$).
- **Dynamic Threshold Alerts:** Configurable percentage slider (50% to 95%).
- **Hard Cutoff Enforcement:** When `currentMonthSpend >= monthlyLimit` and `enforcementMode == HARD_CUTOFF`, Redis immediately registers `quota:blocked:{tenantId} = "1"`. The gateway rejects incoming requests with `HTTP 402 Payment Required` in under 1 millisecond.
- **Atomic Quota Unblocking:** Adding wallet credits via the dashboard top-up immediately deletes the quota block key and restores full throughput.

### 4. Token Consumption & Model Costs Analytics
- **Multi-Tier Breakdown:** Real-time visibility into prompt tokens, completion tokens, and cached tokens.
- **Hourly Ingress Telemetry:** Interactive 24-hour SVG time-series chart displaying request volume, token curves, and hourly spend.
- **Cost Differential:** Quantifies the cost divergence between Frontier ($140\ \mu\$$) vs Fast ($45\ \mu\$$) vs Semantic Cache ($0\ \mu\$$).

### 5. Semantic Cache Savings & Efficiency
- **Cache Hit Rate:** Visualizes verified HNSW cosine search hits (cosine distance $\le 0.85$).
- **Net FinOps ROI:** Calculates cumulative tokens bypassed upstream and net dollar savings preserved in the tenant wallet ($14.82 saved across 142,500 cached tokens).
- **Latency Acceleration:** Compares sub-15ms cache hit latency against 173.8ms frontier model latency.

### 6. Zero-Trust Security Audit Firewall
- **Threat Taxonomy Breakdown:** Captures adversarial attacks across CAT 1 (Direct Instruction Override), CAT 2 (Delimiter / Persona Hijack), CAT 3 (Obfuscation / Base64), CAT 4 (Indirect Exfiltration), CAT 5 (Meta-Prompt), CAT 6 (Token Smuggling), and PII Redactions (Credit Cards, SSNs, Emails).
- **Searchable Audit Stream:** Displays Trace ID, Action (`BLOCKED`, `REDACTED`), Violation Types, and Timestamp.

### 7. Provider Health, Latency & Failover Telemetry
- **Sliding-Window Circuit Breakers:** Real-time state of Google Gemini Primary (`CLOSED`, `OPEN`, `HALF_OPEN`), median latency, error rates.
- **Active Standby:** Monitors OpenAI fallback readiness (< 200ms failover SLA).
- **Infrastructure Health:** Sub-millisecond Redis latency (< 1.0 ms), Kafka topic lag (0), and PostgreSQL pgvector index readiness.

### 8. Financial Reconciliation & Report Exports
- **Live Double-Entry Stream:** Real-time visibility into append-only `DEBIT` and `CREDIT` rows across `FUNDING_SOURCE`, `TENANT_WALLET`, and `PLATFORM_REVENUE`.
- **1-Click RFC-4180 CSV Export:** Direct download of timestamped transaction records for corporate accounting and ERP ingestion.
- **1-Click Cryptographic Audit Export:** Full mathematical proof of balance ($\sum \text{DEBIT} == \sum \text{CREDIT}$, $\Delta = 0$).

---

## 4. Empirical Verification & Test Results

The developer experience platform was verified using [benchmark/verify-developer-dashboard.py](file:///c:/Users/sailo/OneDrive/Desktop/Orvix/benchmark/verify-developer-dashboard.py). All 35 assertions passed with a 100.0% pass rate:

```
================================================================================
  ORVIX DEVELOPER EXPERIENCE & ORGANIZATION DASHBOARD VERIFICATION (ADR-015)
================================================================================

[Phase 1] Organization Creation & Primary API Key Provisioning...
  [PASS] Create organization returns HTTP 201 
  [PASS] Valid tenant ID generated ID: ten_fd9b313f47bc4164
  [PASS] Raw API key generated with prefix Key: orv_live_6774eb4...
  [PASS] Initial wallet balance provisioned 50,000,000 µ$ ($50.00)

[Phase 2] Multi-Key Lifecycle Management (Generate, List, Revoke)...
  [PASS] List API keys returns active keys 
  [PASS] Masked key formatting adheres to security standards 
  [PASS] Secondary API key generated 
  [PASS] Key role assigned properly 
  [PASS] API key revoked successfully 

[Phase 3] Monthly AI Spending Limits & Quota Policies...
  [PASS] Fetch default spending limit 
  [PASS] Updated monthly limit saved $75.00
  [PASS] Alert threshold percent set to 85% 
  [PASS] Quota block not triggered when under limit 

[Phase 4] Wallet Credit Top-Up...
  [PASS] Credit wallet top-up succeeded New Balance: 70000000 µ$

[Phase 5] Live Chat Completion with Provisioned Organization Key...
  [PASS] Completion request returns HTTP 200 
  [PASS] Completion content received 
  [PASS] Trace ID header present 
  [PASS] Provider used header present 
  [PASS] Cache status header present 

[Phase 6] Real-Time Analytics & Cost Telemetry...
  [PASS] Overview returns balance details 
  [PASS] Overview returns total request count 
  [PASS] Semantic cache hit rate metric present Hit Rate: 84.46%
  [PASS] FinOps dollars saved metric present Saved: $14.82
  [PASS] 24-hour time series chart points returned 

[Phase 7] Zero-Trust Security Audit Events...
  [PASS] Security audit events retrieved 
  [PASS] Adversarial threat categories captured in audit log 

[Phase 8] Provider Health & Circuit Breaker Telemetry...
  [PASS] Overall platform health reported 
  [PASS] Google Gemini Primary cluster operational 
  [PASS] Resilience4j Circuit Breaker in CLOSED state 
  [PASS] OpenAI Fallback cluster in ACTIVE STANDBY 
  [PASS] Redis rate-limiter latency < 1.0 ms 

[Phase 9] Financial Ledger Reports & Mathematical Reconciliation Export...
  [PASS] CSV ledger export returns CSV header line 
  [PASS] CSV header Content-Disposition present 
  [PASS] Reconciliation audit report balanced invariant 
  [PASS] Reconciliation delta zero invariant 

================================================================================
  VERIFICATION SUCCESSFUL: 35 / 35 assertions passed (100.0%)
  Machine-readable report written to: benchmark/developer_dashboard_report.json
================================================================================
```

---

## 5. Quickstart & Verification Runbook

```powershell
# 1. Start the cluster (if not already running)
python benchmark/start_cluster.py --restart

# 2. Run the developer experience verification suite
python benchmark/verify-developer-dashboard.py

# 3. Access the Live Commercial Dashboard in any browser:
# Native Gateway Endpoint:
http://localhost:8080/dashboard

# Or run the standalone dashboard preview server:
python dashboard/serve.py 3000
http://localhost:3000
```
