package com.orvix.common.contract.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record RefundRequest(
        @NotBlank(message = "referenceTransactionId is required")
        String referenceTransactionId,

        @Positive(message = "amountMicros must be positive")
        long amountMicros,

        String reason
) {}
