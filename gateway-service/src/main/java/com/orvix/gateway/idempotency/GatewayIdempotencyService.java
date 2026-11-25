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

    /**
     * Checks if a request with this idempotency key was already completed for this tenant.
     * Returns the cached ChatCompletionResponse if found.
     */
    public ChatCompletionResponse getCachedResponse(String tenantId, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return null;
        }
        String key = PREFIX + tenantId + ":" + idempotencyKey;
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json != null && !json.isBlank()) {
                log.info("Idempotency key hit for tenant: {}, key: {}. Replaying cached response.", tenantId, idempotencyKey);
                return objectMapper.readValue(json, ChatCompletionResponse.class);
            }
        } catch (Exception ex) {
            log.warn("Redis idempotency cache read failure for tenant {}: {}", tenantId, ex.getMessage());
        }
        return null;
    }

    /**
     * Stores completed response for 24 hours to prevent duplicate LLM calls or charges.
     */
    public void storeResponse(String tenantId, String idempotencyKey, ChatCompletionResponse response) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || response == null) {
            return;
        }
        String key = PREFIX + tenantId + ":" + idempotencyKey;
        try {
            String json = objectMapper.writeValueAsString(response);
            redisTemplate.opsForValue().set(key, json, IDEMPOTENCY_TTL);
            log.debug("Stored idempotency record for tenant: {}, key: {}", tenantId, idempotencyKey);
        } catch (Exception ex) {
            log.warn("Redis idempotency cache write failure for tenant {}: {}", tenantId, ex.getMessage());
        }
    }
}
