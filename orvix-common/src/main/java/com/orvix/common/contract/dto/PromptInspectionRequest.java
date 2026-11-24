package com.orvix.common.contract.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record PromptInspectionRequest(
        @NotBlank String traceId,
        @NotBlank String tenantId,
        @NotBlank String prompt
) {}
