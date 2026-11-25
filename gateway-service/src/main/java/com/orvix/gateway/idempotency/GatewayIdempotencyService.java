package com.orvix.gateway.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orvix.common.contract.dto.ChatCompletionResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
@RequiredArgsConstructor
@Slf4j
public class GatewayIdempotencyService {

    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);
    private static final String PREFIX = "idemp:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final DurableIdempotencyStore durableStore;

    /**
     * Checks if a request with this idempotency key was already completed for this tenant.
     * Dual-Tier Resolution (ADR-016):
     *   Tier-1 (L1): Fast in-memory Redis cache (< 1ms).
     *   Tier-2 (L2): Durable persistent disk WAL fallback to survive Redis eviction or restart.
     */
    public ChatCompletionResponse getCachedResponse(String tenantId, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return null;
        }
        String key = PREFIX + tenantId + ":" + idempotencyKey;

        // 1. Check Tier-1 Redis Cache
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json != null && !json.isBlank()) {
                log.info("IDEMPOTENCY_L1_HIT: Fast Redis cache hit for tenant: {}, key: {}. Replaying response.", tenantId, idempotencyKey);
                return objectMapper.readValue(json, ChatCompletionResponse.class);
            }
        } catch (Exception ex) {
            log.warn("Redis idempotency cache read failure (falling back to L2 durable store) for tenant {}: {}", tenantId, ex.getMessage());
        }

        // 2. Check Tier-2 Durable Persistent Disk Store
        ChatCompletionResponse durableResponse = durableStore.get(tenantId, idempotencyKey);
        if (durableResponse != null) {
            log.info("IDEMPOTENCY_L2_HIT: Recovered idempotency replay from durable disk store after Redis eviction/restart: tenant={}, key={}",
                    tenantId, idempotencyKey);
            // Re-warm Redis L1 cache
            try {
                String json = objectMapper.writeValueAsString(durableResponse);
                redisTemplate.opsForValue().set(key, json, IDEMPOTENCY_TTL);
            } catch (Exception ignore) {}
            return durableResponse;
        }

        return null;
    }

    /**
     * Stores completed response into both Tier-1 Redis and Tier-2 Durable Disk Store.
     */
    public void storeResponse(String tenantId, String idempotencyKey, ChatCompletionResponse response) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || response == null) {
            return;
        }
        String key = PREFIX + tenantId + ":" + idempotencyKey;

        // 1. Store in Tier-1 Redis (24h TTL)
        try {
            String json = objectMapper.writeValueAsString(response);
            redisTemplate.opsForValue().set(key, json, IDEMPOTENCY_TTL);
            log.debug("Stored Tier-1 Redis idempotency record for tenant: {}, key: {}", tenantId, idempotencyKey);
        } catch (Exception ex) {
            log.warn("Redis idempotency cache write failure for tenant {}: {}", tenantId, ex.getMessage());
        }

        // 2. Persist in Tier-2 Durable Disk WAL (survives Redis restart/eviction)
        try {
            durableStore.put(tenantId, idempotencyKey, response);
        } catch (Exception ex) {
            log.error("Failed to store Tier-2 durable idempotency record: {}", ex.getMessage());
        }
    }
}
