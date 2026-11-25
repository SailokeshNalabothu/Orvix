package com.orvix.common.contract.dto;

import java.util.List;

public record ProviderHealthDto(
        ComponentHealth geminiPrimary,
        ComponentHealth openAiFallback,
        ComponentHealth redis,
        ComponentHealth kafka,
        ComponentHealth postgres,
        String overallStatus,
        long timestampMs
) {
    public record ComponentHealth(
            String name,
            String status, // OPERATIONAL, STANDBY, DEGRADED, FAILING
            String detail,
            double latencyMs,
            String circuitBreakerState // CLOSED, OPEN, HALF_OPEN, N/A
    ) {}
}
