package com.orvix.gateway.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.orvix.common.contract.dto.ChatCompletionResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GatewayIdempotencyServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private DurableIdempotencyStore durableStore;
    private GatewayIdempotencyService idempotencyService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        durableStore = new DurableIdempotencyStore(objectMapper, tempDir.toString());
        durableStore.init();

        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        idempotencyService = new GatewayIdempotencyService(redisTemplate, objectMapper, durableStore);
    }

    @Test
    @DisplayName("Idempotency: Replays from Tier-2 Durable Store when Redis evicted or restarted")
    void shouldRecoverFromDurableStoreWhenRedisEvicted() {
        String tenantId = "ten_acme";
        String idempotencyKey = "idem_req_abc_123";

        ChatCompletionResponse response = new ChatCompletionResponse(
                "chatcmpl_1", "gemini-primary", "gemini-1.5-pro", "Cached content",
                new ChatCompletionResponse.GuardrailSummary("CLEAN", 0),
                new ChatCompletionResponse.UsageSummary(10, 20, 30, 45L),
                85L
        );

        // Store into both Redis and Durable Store
        idempotencyService.storeResponse(tenantId, idempotencyKey, response);
        assertEquals(1, durableStore.size());

        // Simulate Redis Cache Eviction (Redis returns null)
        when(valueOperations.get("idemp:" + tenantId + ":" + idempotencyKey)).thenReturn(null);

        // Fetch cached response
        ChatCompletionResponse recovered = idempotencyService.getCachedResponse(tenantId, idempotencyKey);

        // Assert response is safely recovered from Durable L2 Disk Store!
        assertNotNull(recovered);
        assertEquals("chatcmpl_1", recovered.id());
        assertEquals("Cached content", recovered.content());

        // Verify Redis L1 was re-warmed
        verify(valueOperations, atLeastOnce()).set(eq("idemp:" + tenantId + ":" + idempotencyKey), any(), any());
    }
}
