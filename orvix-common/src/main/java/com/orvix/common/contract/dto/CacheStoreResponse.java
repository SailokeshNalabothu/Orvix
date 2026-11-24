package com.orvix.common.contract.dto;

public record CacheStoreResponse(
        boolean stored,
        Long cacheId
) {}
