package com.orvix.common.contract.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record RefundRequest(
        @NotBlank(message = "referenceTransactionId is required")
        String referenceTransactionId,

        @Positive(message = "amountMicros must be positive")
        long amountMicros,

        String reason,

        String idempotencyKey
) {
    public RefundRequest(String referenceTransactionId, long amountMicros, String reason) {
        this(referenceTransactionId, amountMicros, reason, null);
    }
}
