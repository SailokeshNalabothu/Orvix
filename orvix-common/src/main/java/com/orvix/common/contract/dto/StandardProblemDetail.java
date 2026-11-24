package com.orvix.common.contract.dto;

import java.time.Instant;

public record StandardProblemDetail(
        String type,
        String title,
        int status,
        String detail,
        String instance,
        String trace_id,
        Instant timestamp
) {}
