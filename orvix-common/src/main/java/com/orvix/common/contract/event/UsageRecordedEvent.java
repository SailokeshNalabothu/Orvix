package com.orvix.common.contract.event;

import java.time.Instant;

public record UsageRecordedEvent(
        String event_id,
        String trace_id,
        String tenant_id,
        String request_id,
        String provider,
        String model,
        boolean cache_hit,
        int prompt_tokens,
        int completion_tokens,
        Instant occurred_at
) {}
