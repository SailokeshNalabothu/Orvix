package com.orvix.common.contract.dto;

public record CacheLookupResponse(
        boolean hit,
        String cachedResponse,
        double similarity,
        String cacheStatus
) {
    public static CacheLookupResponse miss() {
        return new CacheLookupResponse(false, null, 0.0, "MISS");
    }

    public static CacheLookupResponse hit(String cachedResponse, double similarity) {
        return new CacheLookupResponse(true, cachedResponse, similarity, "HIT");
    }

    public static CacheLookupResponse ineligible(String reason) {
        return new CacheLookupResponse(false, null, 0.0, "INELIGIBLE");
    }
}
