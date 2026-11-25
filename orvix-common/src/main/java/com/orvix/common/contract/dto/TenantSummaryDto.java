package com.orvix.common.contract.dto;

import java.time.Instant;

public record TenantSummaryDto(
        String tenantId,
        String name,
        String status,
        int rpmLimit,
        int tpmLimit,
        long balanceMicros,
        Instant createdAt
) {
    public double getBalanceUsd() {
        return balanceMicros / 1_000_000.0;
    }
}
