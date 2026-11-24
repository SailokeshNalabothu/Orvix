package com.orvix.common.contract.dto;

import java.time.Instant;

public record CreditWalletResponse(
        String tenantId,
        long previousBalanceMicros,
        long creditedAmountMicros,
        long newBalanceMicros,
        String transactionId,
        Instant creditedAt
) {}
