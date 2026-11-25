package com.orvix.common.contract.dto;

public record DashboardOverviewDto(
        String tenantId,
        String organizationName,
        long balanceMicros,
        double balanceUsd,
        long monthlyLimitMicros,
        double monthlyLimitUsd,
        long currentMonthSpendMicros,
        double currentMonthSpendUsd,
        int alertThresholdPercent,
        String enforcementMode,
        boolean isQuotaBlocked,
        long totalRequests,
        long promptTokens,
        long completionTokens,
        long cachedTokensSaved,
        long microDollarsSaved,
        double dollarsSaved,
        double cacheHitRatePercent,
        long securityEventsCount,
        int activeApiKeysCount,
        String primaryProviderStatus,
        String circuitBreakerState
) {}
