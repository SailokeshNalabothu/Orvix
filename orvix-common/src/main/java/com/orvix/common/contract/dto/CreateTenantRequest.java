package com.orvix.common.contract.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateTenantRequest(
        @NotBlank String name,
        @NotNull @Min(1) Integer rpmLimit,
        @NotNull @Min(1) Integer tpmLimit,
        Long initialBalanceMicros
) {
    public long getEffectiveInitialBalance() {
        return initialBalanceMicros != null && initialBalanceMicros > 0 ? initialBalanceMicros : 0L;
    }
}
