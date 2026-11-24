package com.orvix.common.contract.dto;

import java.time.Instant;

public record CreateTenantResponse(
        String tenantId,
        String name,
        String rawApiKey,
        int rpmLimit,
        int tpmLimit,
        String status,
        long balanceMicros,
        Instant createdAt
) {}
