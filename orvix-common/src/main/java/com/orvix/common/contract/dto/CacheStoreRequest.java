package com.orvix.common.contract.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CacheStoreRequest(
        @NotBlank String tenantId,
        @NotBlank String model,
        @NotBlank String prompt,
        @NotBlank String responseText,
        Long ttlSeconds
) {
    public long getEffectiveTtlSeconds() {
        return ttlSeconds != null && ttlSeconds > 0 ? ttlSeconds : 86400L; // default 24h
    }
}
