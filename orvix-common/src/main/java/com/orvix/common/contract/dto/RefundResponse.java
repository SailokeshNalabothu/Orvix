package com.orvix.common.contract.dto;

import java.time.Instant;

public record RefundResponse(
        String tenantId,
        String refundTransactionId,
        String referenceTransactionId,
        long refundedAmountMicros,
        long previousBalanceMicros,
        long newBalanceMicros,
        Instant createdAt
) {}
