package com.orvix.gateway.controller;

import com.orvix.common.contract.dto.*;
import com.orvix.gateway.service.DashboardService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/v1/dashboard")
@RequiredArgsConstructor
@Slf4j
public class DashboardApiController {

    private final DashboardService dashboardService;

    // 1. Organization & Tenant Management
    @PostMapping("/tenants")
    public ResponseEntity<CreateTenantResponse> createOrganization(@Valid @RequestBody CreateTenantRequest request) {
        CreateTenantResponse response = dashboardService.createOrganization(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/tenants")
    public ResponseEntity<List<TenantSummaryDto>> listOrganizations() {
        return ResponseEntity.ok(dashboardService.listOrganizations());
    }

    @GetMapping("/tenants/{tenantId}")
    public ResponseEntity<TenantSummaryDto> getOrganization(@PathVariable String tenantId) {
        return ResponseEntity.ok(dashboardService.getOrganization(tenantId));
    }

    // 2. API Key Management
    @GetMapping("/tenants/{tenantId}/api-keys")
    public ResponseEntity<List<ApiKeyMetadataDto>> listApiKeys(@PathVariable String tenantId) {
        return ResponseEntity.ok(dashboardService.listApiKeys(tenantId));
    }

    @PostMapping("/tenants/{tenantId}/api-keys")
    public ResponseEntity<DashboardService.GeneratedApiKeyResult> generateApiKey(
            @PathVariable String tenantId,
            @RequestBody(required = false) Map<String, String> body
    ) {
        String keyName = body != null ? body.get("name") : null;
        String role = body != null ? body.get("role") : null;
        var result = dashboardService.generateApiKey(tenantId, keyName, role);
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    @DeleteMapping("/tenants/{tenantId}/api-keys/{keyId}")
    public ResponseEntity<Map<String, Object>> revokeApiKey(
            @PathVariable String tenantId,
            @PathVariable String keyId
    ) {
        boolean revoked = dashboardService.revokeApiKey(tenantId, keyId);
        return ResponseEntity.ok(Map.of("revoked", revoked, "keyId", keyId));
    }

    // 3. Spending Limits & Quota Controls
    @GetMapping("/spending-limits/{tenantId}")
    public ResponseEntity<SpendingLimitDto> getSpendingLimit(@PathVariable String tenantId) {
        return ResponseEntity.ok(dashboardService.getSpendingLimit(tenantId));
    }

    @PutMapping("/spending-limits/{tenantId}")
    public ResponseEntity<SpendingLimitDto> updateSpendingLimit(
            @PathVariable String tenantId,
            @RequestBody SpendingLimitDto request
    ) {
        return ResponseEntity.ok(dashboardService.updateSpendingLimit(tenantId, request));
    }

    @PostMapping("/tenants/{tenantId}/topup")
    public ResponseEntity<CreditWalletResponse> topupCredits(
            @PathVariable String tenantId,
            @RequestBody Map<String, Object> body
    ) {
        long amountMicros = 10_000_000L;
        if (body != null && body.containsKey("amountMicros")) {
            amountMicros = Long.parseLong(body.get("amountMicros").toString());
        }
        String reason = body != null && body.containsKey("reason") ? body.get("reason").toString() : "DASHBOARD_TOPUP";
        return ResponseEntity.ok(dashboardService.topupCredits(tenantId, amountMicros, reason));
    }

    // 4. Analytics & Overview
    @GetMapping("/analytics/overview")
    public ResponseEntity<DashboardOverviewDto> getOverview(
            @RequestParam(defaultValue = "ten_demo_tenant") String tenantId
    ) {
        return ResponseEntity.ok(dashboardService.getOverview(tenantId));
    }

    @GetMapping("/analytics/usage-chart")
    public ResponseEntity<List<UsageTimePointDto>> getUsageChart(
            @RequestParam(defaultValue = "ten_demo_tenant") String tenantId,
            @RequestParam(defaultValue = "24h") String timeframe
    ) {
        return ResponseEntity.ok(dashboardService.getUsageChart(tenantId, timeframe));
    }

    // 5. Security Audit Events
    @GetMapping("/security/events")
    public ResponseEntity<List<SecurityEventDto>> getSecurityEvents(
            @RequestParam(defaultValue = "ten_demo_tenant") String tenantId
    ) {
        return ResponseEntity.ok(dashboardService.getSecurityEvents(tenantId));
    }

    // 6. Provider Health & Infrastructure Monitoring
    @GetMapping("/providers/health")
    public ResponseEntity<ProviderHealthDto> getProviderHealth() {
        return ResponseEntity.ok(dashboardService.getProviderHealth());
    }

    // 7. Financial Reports & Ledger Export
    @GetMapping(value = "/export/ledger.csv", produces = "text/csv")
    public ResponseEntity<String> exportLedgerCsv(
            @RequestParam(defaultValue = "ten_demo_tenant") String tenantId
    ) {
        String csvData = dashboardService.exportLedgerCsv(tenantId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"orvix-ledger-" + tenantId + ".csv\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(csvData);
    }

    @GetMapping("/export/reconciliation.json")
    public ResponseEntity<ReconciliationReport> getReconciliationReport() {
        return ResponseEntity.ok(dashboardService.getReconciliationReport());
    }
}
