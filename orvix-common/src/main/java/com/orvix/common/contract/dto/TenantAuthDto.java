package com.orvix.common.contract.dto;

public record TenantAuthDto(
        String tenantId,
        String name,
        int rpmLimit,
        int tpmLimit,
        String status
) {}
