package com.orvix.billing.controller;

import com.orvix.billing.model.Tenant;
import com.orvix.billing.repository.TenantRepository;
import com.orvix.common.contract.dto.TenantAuthDto;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1/tenants")
@RequiredArgsConstructor
public class InternalTenantController {

    private final TenantRepository tenantRepository;

    @GetMapping("/by-key/{apiKeyHash}")
    public ResponseEntity<TenantAuthDto> getTenantByApiKeyHash(@PathVariable("apiKeyHash") String apiKeyHash) {
        return tenantRepository.findByApiKeyHash(apiKeyHash)
                .map(tenant -> ResponseEntity.ok(new TenantAuthDto(
                        tenant.getId(),
                        tenant.getName(),
                        tenant.getRpmLimit(),
                        tenant.getTpmLimit(),
                        tenant.getStatus().name()
                )))
                .orElse(ResponseEntity.notFound().build());
    }
}
