package com.orvix.gateway.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisRateLimiterTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    private RedisRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        rateLimiter = new RedisRateLimiter(redisTemplate);
    }

    @Test
    void shouldAllowRequestWhenTokensAvailable() {
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(List.of(1L, 59L, 0L));

        var result = rateLimiter.checkRateLimit("tenant-1", 60, 1);

        assertTrue(result.allowed());
        assertEquals(59L, result.remainingTokens());
        assertEquals(0L, result.retryAfterSeconds());
    }

    @Test
    void shouldRejectRequestWhenTokensExhausted() {
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(List.of(0L, 0L, 12L));

        var result = rateLimiter.checkRateLimit("tenant-1", 60, 1);

        assertFalse(result.allowed());
        assertEquals(0L, result.remainingTokens());
        assertEquals(12L, result.retryAfterSeconds());
    }

    @Test
    void shouldThrowFailClosedExceptionWhenRedisFails() {
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("Redis connection refused"));

        assertThrows(RedisRateLimiter.RedisUnavailableException.class, () ->
                rateLimiter.checkRateLimit("tenant-1", 60, 1)
        );
    }
}
