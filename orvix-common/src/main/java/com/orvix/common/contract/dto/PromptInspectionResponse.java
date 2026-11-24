package com.orvix.common.contract.dto;

import java.util.List;

public record PromptInspectionResponse(
        String status,
        String sanitizedPrompt,
        int redactionsCount,
        List<String> violations
) {
    public static final String STATUS_CLEAN = "CLEAN";
    public static final String STATUS_REDACTED = "REDACTED";
    public static final String STATUS_BLOCKED = "BLOCKED";
}
