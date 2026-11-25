package com.orvix.common.contract.dto;

import java.time.Instant;
import java.util.List;

public record TenantSecurityPolicyDto(
        String tenantId,
        double riskThreshold,
        boolean strictMode,
        boolean blockObfuscation,
        boolean strictPiiScrubbing,
        List<String> customBlockedKeywords,
        List<String> allowedKeywords,
        Instant updatedAt
) {
    public static final double DEFAULT_RISK_THRESHOLD = 0.60;
    public static final double STRICT_RISK_THRESHOLD = 0.45;

    public static TenantSecurityPolicyDto defaultPolicy(String tenantId) {
        return new TenantSecurityPolicyDto(
                tenantId,
                DEFAULT_RISK_THRESHOLD,
                false,
                true,
                true,
                List.of(),
                List.of(),
                Instant.now()
        );
    }
}
