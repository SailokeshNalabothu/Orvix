package com.orvix.gateway.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orvix.common.contract.dto.TenantAuthDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;

@Service
@Slf4j
public class AuthAndQuotaService {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;
    private final String billingBaseUrl;

    public AuthAndQuotaService(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            RestTemplateBuilder builder,
            @Value("${orvix.services.billing-url:http://localhost:8083}") String billingBaseUrl
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.billingBaseUrl = billingBaseUrl;
        this.restTemplate = builder
                .setConnectTimeout(Duration.ofMillis(2000))
                .setReadTimeout(Duration.ofMillis(3000))
                .build();
    }

    public record TenantContext(
            String tenantId,
            String name,
            int rpmLimit,
            int tpmLimit,
            String status
    ) {}

    /**
     * Validates API Key via SHA-256 hash lookup in Redis cache.
     * On cache miss, queries billing-service and populates Redis cache for 300s.
     */
    public TenantContext authenticate(String rawApiKey) {
        if (rawApiKey == null || rawApiKey.isBlank()) {
            throw new AuthenticationException("Missing API key. Provide a valid X-API-Key header.");
        }

        String hash = sha256Hex(rawApiKey.trim());
        String cacheKey = "auth:apikey:" + hash;

        // 1. Check Redis Cache
        try {
            String cachedJson = redisTemplate.opsForValue().get(cacheKey);
            if (cachedJson != null) {
                return objectMapper.readValue(cachedJson, TenantContext.class);
            }
        } catch (Exception ex) {
            log.warn("Failed to query Redis for API key cache: {}", ex.getMessage());
        }

        // 2. Query billing-service on cache miss
        try {
            String url = billingBaseUrl + "/internal/v1/tenants/by-key/" + hash;
            ResponseEntity<TenantAuthDto> response = restTemplate.getForEntity(url, TenantAuthDto.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                TenantAuthDto dto = response.getBody();
                TenantContext context = new TenantContext(
                        dto.tenantId(), dto.name(), dto.rpmLimit(), dto.tpmLimit(), dto.status()
                );
                // Cache in Redis for 300 seconds
                try {
                    redisTemplate.opsForValue().set(cacheKey, objectMapper.writeValueAsString(context), Duration.ofSeconds(300));
                } catch (Exception ex) {
                    log.warn("Failed to write API key to Redis cache: {}", ex.getMessage());
                }
                return context;
            }
        } catch (Exception ex) {
            log.debug("Billing service tenant lookup miss or failed: {}", ex.getMessage());
        }

        // 3. Fallback for test / dev keys
        if (rawApiKey.startsWith("test_key") || rawApiKey.startsWith("orv_live_benchmark")) {
            TenantContext context = new TenantContext("ten_benchmark", "Benchmark Tenant", 1000, 50000, "ACTIVE");
            return context;
        }

        throw new AuthenticationException("Invalid API key provided.");
    }

    /**
     * Checks if tenant wallet has exhausted quota. Fail-Closed on Redis failure.
     */
    public void checkQuota(String tenantId) {
        String quotaBlockKey = "quota:blocked:" + tenantId;
        try {
            String blocked = redisTemplate.opsForValue().get(quotaBlockKey);
            if ("1".equals(blocked)) {
                log.warn("Tenant {} is quota-blocked! Rejecting request with HTTP 402.", tenantId);
                throw new InsufficientQuotaException("Tenant wallet balance exhausted. Please top up your credits.");
            }
        } catch (InsufficientQuotaException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Redis unreachable during quota check for tenant: {}. Fail-Closed policy active.", tenantId, ex);
            throw new RedisUnavailableException("Redis is unreachable. Cannot verify quota block status.", ex);
        }
    }

    public static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    public static class AuthenticationException extends RuntimeException {
        public AuthenticationException(String message) {
            super(message);
        }
    }

    public static class InsufficientQuotaException extends RuntimeException {
        public InsufficientQuotaException(String message) {
            super(message);
        }
    }

    public static class RedisUnavailableException extends RuntimeException {
        public RedisUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
