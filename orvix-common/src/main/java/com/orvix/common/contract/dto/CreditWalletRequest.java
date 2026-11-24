package com.orvix.common.contract.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record CreditWalletRequest(
        @NotNull @Min(1) Long amountMicros,
        String reason
) {}
