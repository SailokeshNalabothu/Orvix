package com.orvix.gateway.ratelimit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

@Component
@Slf4j
public class RedisRateLimiter {

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<List> tokenBucketScript;

    public RedisRateLimiter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.tokenBucketScript = new DefaultRedisScript<>();
        this.tokenBucketScript.setScriptSource(new ResourceScriptSource(new ClassPathResource("scripts/token_bucket.lua")));
        this.tokenBucketScript.setResultType(List.class);
    }

    public record RateLimitResult(
            boolean allowed,
            long remainingTokens,
            long retryAfterSeconds
    ) {}

    public RateLimitResult checkRateLimit(String tenantId, int rpmLimit, int requestedTokens) {
        String key = "rl:rpm:" + tenantId;
        double refillRatePerSec = (double) rpmLimit / 60.0;
        long nowMs = System.currentTimeMillis();

        try {
            List<Long> result = (List<Long>) redisTemplate.execute(
                    tokenBucketScript,
                    Collections.singletonList(key),
                    String.valueOf(rpmLimit),
                    String.valueOf(refillRatePerSec),
                    String.valueOf(nowMs),
                    String.valueOf(requestedTokens)
            );

            if (result == null || result.size() < 3) {
                log.warn("Redis Lua script returned unexpected result for key: {}", key);
                return new RateLimitResult(true, rpmLimit, 0);
            }

            boolean allowed = result.get(0) == 1L;
            long remaining = result.get(1);
            long retryAfter = result.get(2);

            return new RateLimitResult(allowed, remaining, retryAfter);
        } catch (Exception ex) {
            log.error("Redis unreachable during rate limit check for tenant: {}. Fail-Closed policy active.", tenantId, ex);
            throw new RedisUnavailableException("Redis is unreachable. Cannot enforce rate limits.", ex);
        }
    }

    public static class RedisUnavailableException extends RuntimeException {
        public RedisUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
