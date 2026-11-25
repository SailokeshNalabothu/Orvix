package com.orvix.billing.controller;

import com.orvix.billing.service.DoubleEntryLedgerService;
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
}
