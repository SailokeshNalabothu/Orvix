package com.orvix.common.contract.dto;

import java.time.Instant;

public record LedgerEntryDto(
        Long id,
        String transactionId,
        String tenantId,
        String accountType,
        String entryType,
        long amountMicros,
        String referenceEventId,
        Instant createdAt
) {}
