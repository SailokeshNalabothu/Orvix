package com.orvix.common.contract.event;

import java.time.Instant;

public record QuotaExhaustedEvent(
        String event_id,
        String tenant_id,
        Instant exhausted_at
) {}
