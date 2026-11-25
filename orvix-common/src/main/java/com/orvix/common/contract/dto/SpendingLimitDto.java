package com.orvix.common.contract.dto;

import java.time.Instant;

public record SpendingLimitDto(
        String tenantId,
        long monthlyLimitMicros,
        long currentMonthSpendMicros,
        int alertThresholdPercent,
        String enforcementMode, // HARD_CUTOFF, SOFT_ALERT
        boolean isQuotaBlocked,
        Instant updatedAt
) {
    public double getMonthlyLimitUsd() {
        return monthlyLimitMicros / 1_000_000.0;
    }

    public double getCurrentMonthSpendUsd() {
        return currentMonthSpendMicros / 1_000_000.0;
    }

    public double getSpendPercentage() {
        if (monthlyLimitMicros <= 0) return 0.0;
        return Math.min(100.0, (double) currentMonthSpendMicros / monthlyLimitMicros * 100.0);
    }
}
