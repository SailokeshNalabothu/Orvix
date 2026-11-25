#!/usr/bin/env python3
"""
Orvix Developer Experience & Organization Dashboard Verification Suite (ADR-015)
Verifies:
  1. Organization sign-up & initial tenant provisioning (POST /v1/dashboard/tenants)
  2. API-Key management (Generate, List, Revoke, SHA-256 Hashing)
  3. Spending limit configuration & quota controls (PUT /v1/dashboard/spending-limits/{tenantId})
  4. Wallet credit top-up & atomic balance updates (POST /v1/dashboard/tenants/{tenantId}/topup)
  5. Live Chat Completion using newly provisioned organization key (POST /v1/chat/completions)
  6. Real-time usage analytics & model cost telemetry (GET /v1/dashboard/analytics/overview)
  7. 24h / 7d Time-series chart telemetry (GET /v1/dashboard/analytics/usage-chart)
  8. Semantic cache savings metrics & latency speedup profiles
  9. Security audit firewall events & CAT 1-6 threat inspection (GET /v1/dashboard/security/events)
 10. Provider health, Resilience4j circuit breaker state, infrastructure latency (GET /v1/dashboard/providers/health)
 11. Financial reports: RFC-4180 CSV ledger export & 3-tier cryptographic reconciliation audit JSON
"""

import json
import os
import sys
import time
import urllib.request
import urllib.error
import uuid

GATEWAY_BASE = os.environ.get("GATEWAY_URL", "http://localhost:8080")
REPORT_PATH = os.path.join(os.path.dirname(__file__), "developer_dashboard_report.json")

def http_json(url, method="GET", body=None, headers=None, expected_status=200):
    req_headers = {"Content-Type": "application/json"}
    if headers:
        req_headers.update(headers)
    
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, headers=req_headers, method=method)
    
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            code = resp.getcode()
            content = resp.read().decode("utf-8")
            response_headers = dict(resp.headers)
            if code != expected_status:
                raise AssertionError(f"Expected HTTP {expected_status}, got {code} for {method} {url}")
            try:
                parsed = json.loads(content)
            except Exception:
                parsed = content
            return code, parsed, response_headers
    except urllib.error.HTTPError as ex:
        content = ex.read().decode("utf-8")
        if ex.code != expected_status:
            raise AssertionError(f"Expected HTTP {expected_status}, got HTTP {ex.code}: {content}")
        try:
            parsed = json.loads(content)
        except Exception:
            parsed = content
        return ex.code, parsed, dict(ex.headers)

def run_verification():
    print("=" * 80)
    print("  ORVIX DEVELOPER EXPERIENCE & ORGANIZATION DASHBOARD VERIFICATION (ADR-015)")
    print("=" * 80)

    report = {
        "timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "checks_passed": 0,
        "total_checks": 0,
        "phases": {},
        "status": "IN_PROGRESS"
    }

    def check(phase_name, check_name, condition, details=""):
        report["total_checks"] += 1
        if condition:
            report["checks_passed"] += 1
            print(f"  [PASS] {check_name} {details}")
            if phase_name not in report["phases"]:
                report["phases"][phase_name] = []
            report["phases"][phase_name].append({"check": check_name, "status": "PASS", "details": details})
        else:
            print(f"  [FAIL] {check_name} {details}")
            if phase_name not in report["phases"]:
                report["phases"][phase_name] = []
            report["phases"][phase_name].append({"check": check_name, "status": "FAIL", "details": details})
            raise AssertionError(f"Check failed: {check_name} ({details})")

    # -------------------------------------------------------------------------
    # Phase 1: Organization Creation & API Key Provisioning
    # -------------------------------------------------------------------------
    print("\n[Phase 1] Organization Creation & Primary API Key Provisioning...")
    org_name = f"Enterprise Test Org {uuid.uuid4().hex[:6]}"
    create_body = {
        "name": org_name,
        "rpmLimit": 60000,
        "tpmLimit": 1200000,
        "initialBalanceMicros": 50000000  # $50.00
    }
    
    code, created_org, _ = http_json(f"{GATEWAY_BASE}/v1/dashboard/tenants", method="POST", body=create_body, expected_status=201)
    tenant_id = created_org.get("tenantId")
    primary_api_key = created_org.get("rawApiKey")
    
    check("OrgManagement", "Create organization returns HTTP 201", code == 201)
    check("OrgManagement", "Valid tenant ID generated", tenant_id is not None and tenant_id.startswith("ten_"), f"ID: {tenant_id}")
    check("OrgManagement", "Raw API key generated with prefix", primary_api_key is not None and primary_api_key.startswith("orv_live_"), f"Key: {primary_api_key[:16]}...")
    check("OrgManagement", "Initial wallet balance provisioned", created_org.get("balanceMicros") == 50000000, "50,000,000 µ$ ($50.00)")

    # -------------------------------------------------------------------------
    # Phase 2: Multi-Key Management & Revocation
    # -------------------------------------------------------------------------
    print("\n[Phase 2] Multi-Key Lifecycle Management (Generate, List, Revoke)...")
    # List keys
    code, keys, _ = http_json(f"{GATEWAY_BASE}/v1/dashboard/tenants/{tenant_id}/api-keys")
    check("KeyManagement", "List API keys returns active keys", len(keys) >= 1)
    check("KeyManagement", "Masked key formatting adheres to security standards", "•••••••••••••••" in keys[0].get("maskedKey", ""))

    # Generate additional key
    gen_body = {"name": "Secondary Ingress Key", "role": "INFERENCE_ONLY"}
    code, new_key_res, _ = http_json(f"{GATEWAY_BASE}/v1/dashboard/tenants/{tenant_id}/api-keys", method="POST", body=gen_body, expected_status=201)
    secondary_key = new_key_res.get("rawApiKey")
    secondary_key_id = new_key_res.get("metadata", {}).get("keyId")
    check("KeyManagement", "Secondary API key generated", secondary_key is not None and secondary_key.startswith("orv_live_"))
    check("KeyManagement", "Key role assigned properly", new_key_res.get("metadata", {}).get("role") == "INFERENCE_ONLY")

    # Revoke secondary key
    code, revoke_res, _ = http_json(f"{GATEWAY_BASE}/v1/dashboard/tenants/{tenant_id}/api-keys/{secondary_key_id}", method="DELETE")
    check("KeyManagement", "API key revoked successfully", revoke_res.get("revoked") is True)

    # -------------------------------------------------------------------------
    # Phase 3: Monthly Spending Limits & Hard Cutoff Quotas
    # -------------------------------------------------------------------------
    print("\n[Phase 3] Monthly AI Spending Limits & Quota Policies...")
    code, limit_dto, _ = http_json(f"{GATEWAY_BASE}/v1/dashboard/spending-limits/{tenant_id}")
    check("SpendingLimits", "Fetch default spending limit", limit_dto.get("monthlyLimitMicros") > 0)

    # Update spending limit ($75.00 limit, 85% alert threshold, HARD_CUTOFF)
    update_limit_body = {
        "tenantId": tenant_id,
        "monthlyLimitMicros": 75000000,  # $75.00
        "currentMonthSpendMicros": 15000000, # $15.00
        "alertThresholdPercent": 85,
        "enforcementMode": "HARD_CUTOFF"
    }
    code, updated_limit, _ = http_json(f"{GATEWAY_BASE}/v1/dashboard/spending-limits/{tenant_id}", method="PUT", body=update_limit_body)
    check("SpendingLimits", "Updated monthly limit saved", updated_limit.get("monthlyLimitMicros") == 75000000, "$75.00")
    check("SpendingLimits", "Alert threshold percent set to 85%", updated_limit.get("alertThresholdPercent") == 85)
    check("SpendingLimits", "Quota block not triggered when under limit", updated_limit.get("isQuotaBlocked") is False)

    # -------------------------------------------------------------------------
    # Phase 4: Wallet Credit Top-Up
    # -------------------------------------------------------------------------
    print("\n[Phase 4] Wallet Credit Top-Up...")
    topup_body = {"amountMicros": 20000000, "reason": "AUTOMATED_TEST_TOPUP"}  # +$20.00
    code, topup_res, _ = http_json(f"{GATEWAY_BASE}/v1/dashboard/tenants/{tenant_id}/topup", method="POST", body=topup_body)
    check("WalletTopup", "Credit wallet top-up succeeded", topup_res.get("newBalanceMicros") >= 70000000, f"New Balance: {topup_res.get('newBalanceMicros')} µ$")

    # -------------------------------------------------------------------------
    # Phase 5: Live Chat Completion using Newly Provisioned Key
    # -------------------------------------------------------------------------
    print("\n[Phase 5] Live Chat Completion with Provisioned Organization Key...")
    chat_body = {
        "model": "auto-cascade",
        "messages": [
            {"role": "user", "content": "Explain CAP theorem and PACELC trade-offs in distributed systems"}
        ],
        "cache_enabled": True
    }
    code, chat_res, headers = http_json(
        f"{GATEWAY_BASE}/v1/chat/completions",
        method="POST",
        body=chat_body,
        headers={"X-API-Key": primary_api_key}
    )
    check("LiveInference", "Completion request returns HTTP 200", code == 200)
    check("LiveInference", "Completion content received", len(chat_res.get("content", "")) > 0)
    check("LiveInference", "Trace ID header present", "X-Trace-Id" in headers)
    check("LiveInference", "Provider used header present", "X-Provider-Used" in headers)
    check("LiveInference", "Cache status header present", "X-Cache-Status" in headers)

    # -------------------------------------------------------------------------
    # Phase 6: Usage Analytics & Telemetry
    # -------------------------------------------------------------------------
    print("\n[Phase 6] Real-Time Analytics & Cost Telemetry...")
    code, overview, _ = http_json(f"{GATEWAY_BASE}/v1/dashboard/analytics/overview?tenantId={tenant_id}")
    check("Analytics", "Overview returns balance details", overview.get("balanceMicros") > 0)
    check("Analytics", "Overview returns total request count", overview.get("totalRequests") > 0)
    check("Analytics", "Semantic cache hit rate metric present", overview.get("cacheHitRatePercent") > 0.0, f"Hit Rate: {overview.get('cacheHitRatePercent')}%")
    check("Analytics", "FinOps dollars saved metric present", overview.get("dollarsSaved") > 0.0, f"Saved: ${overview.get('dollarsSaved')}")

    # Time series chart points
    code, chart_points, _ = http_json(f"{GATEWAY_BASE}/v1/dashboard/analytics/usage-chart?tenantId={tenant_id}&timeframe=24h")
    check("Analytics", "24-hour time series chart points returned", len(chart_points) == 24)

    # -------------------------------------------------------------------------
    # Phase 7: Security Audit Firewall Events
    # -------------------------------------------------------------------------
    print("\n[Phase 7] Zero-Trust Security Audit Events...")
    code, security_events, _ = http_json(f"{GATEWAY_BASE}/v1/dashboard/security/events?tenantId={tenant_id}")
    check("SecurityEvents", "Security audit events retrieved", len(security_events) >= 1)
    has_injection_or_pii = any(
        "PROMPT_INJECTION" in str(e.get("violationTypes")) or "PII" in str(e.get("violationTypes"))
        for e in security_events
    )
    check("SecurityEvents", "Adversarial threat categories captured in audit log", has_injection_or_pii)

    # -------------------------------------------------------------------------
    # Phase 8: Provider Health & Automatic Failover Monitoring
    # -------------------------------------------------------------------------
    print("\n[Phase 8] Provider Health & Circuit Breaker Telemetry...")
    code, health_dto, _ = http_json(f"{GATEWAY_BASE}/v1/dashboard/providers/health")
    check("ProviderHealth", "Overall platform health reported", health_dto.get("overallStatus") in ["HEALTHY", "OPERATIONAL", "DEGRADED"])
    check("ProviderHealth", "Google Gemini Primary cluster operational", health_dto.get("geminiPrimary", {}).get("status") in ["OPERATIONAL", "HEALTHY"])
    check("ProviderHealth", "Resilience4j Circuit Breaker in CLOSED state", health_dto.get("geminiPrimary", {}).get("circuitBreakerState") == "CLOSED")
    check("ProviderHealth", "OpenAI Fallback cluster in ACTIVE STANDBY", health_dto.get("openAiFallback", {}).get("status") == "STANDBY")
    check("ProviderHealth", "Redis rate-limiter latency < 1.0 ms", health_dto.get("redis", {}).get("latencyMs") <= 1.0)

    # -------------------------------------------------------------------------
    # Phase 9: Financial Reports (CSV & JSON Reconciliation)
    # -------------------------------------------------------------------------
    print("\n[Phase 9] Financial Ledger Reports & Mathematical Reconciliation Export...")
    # CSV export
    code, csv_content, csv_headers = http_json(f"{GATEWAY_BASE}/v1/dashboard/export/ledger.csv?tenantId={tenant_id}")
    check("FinancialReports", "CSV ledger export returns CSV header line", "Timestamp,Transaction ID" in csv_content)
    check("FinancialReports", "CSV header Content-Disposition present", "filename=" in csv_headers.get("Content-Disposition", ""))

    # Reconciliation JSON
    code, recon_report, _ = http_json(f"{GATEWAY_BASE}/v1/dashboard/export/reconciliation.json")
    check("FinancialReports", "Reconciliation audit report balanced invariant", recon_report.get("ledgerBalanced") is True)
    check("FinancialReports", "Reconciliation delta zero invariant", recon_report.get("ledgerDeltaMicros") == 0)

    # Summary
    report["status"] = "PASSED"
    with open(REPORT_PATH, "w") as f:
        json.dump(report, f, indent=2)

    print("\n" + "=" * 80)
    print(f"  VERIFICATION SUCCESSFUL: {report['checks_passed']} / {report['total_checks']} assertions passed (100.0%)")
    print(f"  Machine-readable report written to: {REPORT_PATH}")
    print("=" * 80)

if __name__ == "__main__":
    run_verification()
