package com.orvix.common.contract.dto;

import java.time.Instant;
import java.util.List;

public record SecurityEventDto(
        Long id,
        String traceId,
        String tenantId,
        String action, // BLOCKED, REDACTED, ALLOWED
        List<String> violationTypes,
        String details,
        Instant createdAt
) {}
