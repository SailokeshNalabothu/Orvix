package com.orvix.guardrail.controller;

import com.orvix.common.contract.dto.PromptInspectionRequest;
import com.orvix.common.contract.dto.PromptInspectionResponse;
import com.orvix.common.contract.dto.TenantSecurityPolicyDto;
import com.orvix.guardrail.model.SecurityAuditLog;
import com.orvix.guardrail.service.GuardrailService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/v1")
@RequiredArgsConstructor
public class GuardrailController {

    private final GuardrailService guardrailService;

    @PostMapping("/inspect")
    public ResponseEntity<PromptInspectionResponse> inspectPrompt(
            @Valid @RequestBody PromptInspectionRequest request
    ) {
        PromptInspectionResponse response = guardrailService.inspect(request);

        if (PromptInspectionResponse.STATUS_BLOCKED.equals(response.status())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
        }
        return ResponseEntity.ok(response);
    }

    @GetMapping("/tenants/{tenantId}/security-policy")
    public ResponseEntity<TenantSecurityPolicyDto> getTenantSecurityPolicy(
            @PathVariable String tenantId
    ) {
        return ResponseEntity.ok(guardrailService.getTenantPolicy(tenantId));
    }

    @PutMapping("/tenants/{tenantId}/security-policy")
    public ResponseEntity<TenantSecurityPolicyDto> updateTenantSecurityPolicy(
            @PathVariable String tenantId,
            @RequestBody TenantSecurityPolicyDto policyDto
    ) {
        return ResponseEntity.ok(guardrailService.updateTenantPolicy(tenantId, policyDto));
    }

    @GetMapping("/tenants/{tenantId}/audit-logs")
    public ResponseEntity<Page<SecurityAuditLog>> getTenantAuditLogs(
            @PathVariable String tenantId,
            @PageableDefault(size = 50) Pageable pageable
    ) {
        return ResponseEntity.ok(guardrailService.getTenantAuditLogs(tenantId, pageable));
    }
}
