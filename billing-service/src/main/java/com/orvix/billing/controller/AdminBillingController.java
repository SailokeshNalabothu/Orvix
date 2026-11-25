package com.orvix.billing.controller;

import com.orvix.billing.service.DoubleEntryLedgerService;
import com.orvix.billing.service.ReconciliationService;
import com.orvix.billing.service.TenantAdminService;
import com.orvix.common.contract.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/admin/v1")
@RequiredArgsConstructor
public class AdminBillingController {

    private final TenantAdminService adminService;
    private final DoubleEntryLedgerService ledgerService;
    private final ReconciliationService reconciliationService;

    @PostMapping("/tenants")
    public ResponseEntity<CreateTenantResponse> createTenant(
            @Valid @RequestBody CreateTenantRequest request
    ) {
        CreateTenantResponse response = adminService.createTenant(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/tenants/{tenantId}/credits")
    public ResponseEntity<CreditWalletResponse> creditWallet(
            @PathVariable("tenantId") String tenantId,
            @Valid @RequestBody CreditWalletRequest request
    ) {
        CreditWalletResponse response = ledgerService.creditWallet(
                tenantId, request.amountMicros(), request.reason()
        );
        return ResponseEntity.ok(response);
    }

    @GetMapping("/tenants/{tenantId}/ledger")
    public ResponseEntity<LedgerPageResponse> getLedger(
            @PathVariable("tenantId") String tenantId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size
    ) {
        LedgerPageResponse response = adminService.getLedger(tenantId, page, size);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/tenants/{tenantId}/refunds")
    public ResponseEntity<RefundResponse> processRefund(
            @PathVariable("tenantId") String tenantId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKeyHeader,
            @Valid @RequestBody RefundRequest request
    ) {
        String effectiveKey = request.idempotencyKey();
        if (effectiveKey == null || effectiveKey.isBlank()) {
            effectiveKey = idempotencyKeyHeader;
        }
        RefundResponse response = ledgerService.processRefund(
                tenantId, request.referenceTransactionId(), request.amountMicros(), request.reason(), effectiveKey
        );
        return ResponseEntity.ok(response);
    }

    @PostMapping("/reconciliation/run")
    public ResponseEntity<ReconciliationReport> runReconciliation() {
        ReconciliationReport report = reconciliationService.runAudit();
        return ResponseEntity.ok(report);
    }

    @GetMapping("/reconciliation/report")
    public ResponseEntity<ReconciliationReport> getReconciliationReport() {
        ReconciliationReport report = reconciliationService.getLastReport();
        return ResponseEntity.ok(report);
    }
}
