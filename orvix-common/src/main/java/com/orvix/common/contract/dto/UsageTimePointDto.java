package com.orvix.common.contract.dto;

public record UsageTimePointDto(
        String timestamp,
        long requests,
        long tokens,
        long costMicros,
        double costUsd
) {}
