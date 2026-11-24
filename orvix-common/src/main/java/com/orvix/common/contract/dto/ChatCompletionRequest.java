package com.orvix.common.contract.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record ChatCompletionRequest(
        @NotNull String model,
        @NotEmpty List<ChatMessage> messages,
        Double temperature,
        Integer max_tokens,
        Boolean cache_enabled
) {
    public record ChatMessage(
            String role,
            String content
    ) {}
}
