package com.orvix.common.contract.dto;

public record ChatCompletionResponse(
        String id,
        String provider,
        String model,
        String content,
        GuardrailSummary guardrail,
        UsageSummary usage,
        long latency_ms
) {
    public record GuardrailSummary(
            String status,
            int redactions_count
    ) {}

    public record UsageSummary(
            int prompt_tokens,
            int completion_tokens,
            int total_tokens,
            long estimated_cost_micros
    ) {}
}
