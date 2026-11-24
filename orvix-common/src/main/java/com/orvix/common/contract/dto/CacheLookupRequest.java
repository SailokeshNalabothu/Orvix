package com.orvix.common.contract.dto;

import jakarta.validation.constraints.NotBlank;

public record CacheLookupRequest(
        @NotBlank String tenantId,
        @NotBlank String model,
        @NotBlank String prompt
) {}
