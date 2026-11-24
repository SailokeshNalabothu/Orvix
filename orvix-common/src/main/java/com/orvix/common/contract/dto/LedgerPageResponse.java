package com.orvix.common.contract.dto;

import java.util.List;

public record LedgerPageResponse(
        String tenantId,
        long currentBalanceMicros,
        List<LedgerEntryDto> entries,
        int page,
        int size,
        long totalElements
) {}
