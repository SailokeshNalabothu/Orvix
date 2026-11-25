package com.orvix.common.contract.dto;

import java.time.Instant;

public record ApiKeyMetadataDto(
        String keyId,
        String tenantId,
        String name,
        String prefix,
        String maskedKey,
        String status,
        String role,
        Instant createdAt,
        Instant lastUsedAt
) {}
