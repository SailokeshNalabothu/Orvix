package com.orvix.gateway.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orvix.common.contract.dto.CacheLookupResponse;
import com.orvix.common.contract.dto.ChatCompletionRequest;
import com.orvix.common.contract.dto.PromptInspectionResponse;
import com.orvix.common.contract.header.OrvixHeaders;
import com.orvix.gateway.auth.AuthAndQuotaService;
import com.orvix.gateway.client.GuardrailClient;
import com.orvix.gateway.client.SemanticCacheClient;
import com.orvix.gateway.event.UsageEventPublisher;
import com.orvix.gateway.idempotency.GatewayIdempotencyService;
import com.orvix.gateway.ratelimit.RedisRateLimiter;
import com.orvix.gateway.router.LlmRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(GatewayController.class)
class GatewayControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AuthAndQuotaService authAndQuotaService;

    @MockBean
    private RedisRateLimiter rateLimiter;

    @MockBean
    private GuardrailClient guardrailClient;

    @MockBean
    private SemanticCacheClient cacheClient;

    @MockBean
    private LlmRouter llmRouter;

    @MockBean
    private UsageEventPublisher usageEventPublisher;

    @MockBean
    private GatewayIdempotencyService idempotencyService;

    private ChatCompletionRequest validRequest;

    @BeforeEach
    void setUp() {
        validRequest = new ChatCompletionRequest(
                "gpt-4o-mini",
                List.of(new ChatCompletionRequest.ChatMessage("user", "Explain idempotency")),
                0.2, 512, true
        );
    }

    @Test
    void shouldCompleteRequestSuccessfully() throws Exception {
        var tenant = new AuthAndQuotaService.TenantContext("ten_1", "Test Tenant", 60, 10000, "ACTIVE");
        when(authAndQuotaService.authenticate("orv_live_validkey")).thenReturn(tenant);
        doNothing().when(authAndQuotaService).checkQuota("ten_1");

        when(rateLimiter.checkRateLimit(eq("ten_1"), eq(60), eq(1)))
                .thenReturn(new RedisRateLimiter.RateLimitResult(true, 59, 0));

        when(guardrailClient.inspect(anyString(), eq("ten_1"), anyString()))
                .thenReturn(new PromptInspectionResponse("CLEAN", "Explain idempotency", 0, List.of()));

        when(cacheClient.lookup(eq("ten_1"), eq("gpt-4o-mini"), anyString()))
                .thenReturn(CacheLookupResponse.miss());

        when(llmRouter.dispatch(eq("gpt-4o-mini"), anyString()))
                .thenReturn(new LlmRouter.LlmExecutionResult("gemini-primary", "Idempotency means...", 10, 20, 71L));

        mockMvc.perform(post("/v1/chat/completions")
                        .header(OrvixHeaders.API_KEY, "orv_live_validkey")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest)))
                .andExpect(status().isOk())
                .andExpect(header().exists(OrvixHeaders.TRACE_ID))
                .andExpect(header().string(OrvixHeaders.CACHE_STATUS, "MISS"))
                .andExpect(header().string(OrvixHeaders.PROVIDER_USED, "gemini-primary"))
                .andExpect(jsonPath("$.provider").value("gemini-primary"))
                .andExpect(jsonPath("$.content").value("Idempotency means..."))
                .andExpect(jsonPath("$.usage.prompt_tokens").value(10))
                .andExpect(jsonPath("$.usage.completion_tokens").value(20));

        verify(usageEventPublisher).publishUsageAsync(any());
    }

    @Test
    void shouldReturn401WhenApiKeyIsInvalid() throws Exception {
        when(authAndQuotaService.authenticate(any()))
                .thenThrow(new AuthAndQuotaService.AuthenticationException("Invalid API key"));

        mockMvc.perform(post("/v1/chat/completions")
                        .header(OrvixHeaders.API_KEY, "bad_key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.title").value("Invalid API Key"));
    }

    @Test
    void shouldReturn402WhenQuotaIsExhausted() throws Exception {
        var tenant = new AuthAndQuotaService.TenantContext("ten_1", "Test Tenant", 60, 10000, "ACTIVE");
        when(authAndQuotaService.authenticate("orv_live_validkey")).thenReturn(tenant);
        doThrow(new AuthAndQuotaService.InsufficientQuotaException("Wallet balance exhausted"))
                .when(authAndQuotaService).checkQuota("ten_1");

        mockMvc.perform(post("/v1/chat/completions")
                        .header(OrvixHeaders.API_KEY, "orv_live_validkey")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest)))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.status").value(402))
                .andExpect(jsonPath("$.title").value("Insufficient Wallet Balance"));
    }

    @Test
    void shouldReturn429WhenRateLimitExceeded() throws Exception {
        var tenant = new AuthAndQuotaService.TenantContext("ten_1", "Test Tenant", 60, 10000, "ACTIVE");
        when(authAndQuotaService.authenticate("orv_live_validkey")).thenReturn(tenant);
        doNothing().when(authAndQuotaService).checkQuota("ten_1");

        when(rateLimiter.checkRateLimit(eq("ten_1"), eq(60), eq(1)))
                .thenReturn(new RedisRateLimiter.RateLimitResult(false, 0, 15));

        mockMvc.perform(post("/v1/chat/completions")
                        .header(OrvixHeaders.API_KEY, "orv_live_validkey")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "15"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.title").value("Rate Limit Exceeded"));
    }

    @Test
    void shouldReturn400WhenPromptInjectionBlocked() throws Exception {
        var tenant = new AuthAndQuotaService.TenantContext("ten_1", "Test Tenant", 60, 10000, "ACTIVE");
        when(authAndQuotaService.authenticate("orv_live_validkey")).thenReturn(tenant);
        doNothing().when(authAndQuotaService).checkQuota("ten_1");
        when(rateLimiter.checkRateLimit(anyString(), anyInt(), anyInt()))
                .thenReturn(new RedisRateLimiter.RateLimitResult(true, 50, 0));

        when(guardrailClient.inspect(anyString(), anyString(), anyString()))
                .thenThrow(new GuardrailClient.PromptInjectionBlockedException("Prompt injection", List.of("PROMPT_INJECTION")));

        mockMvc.perform(post("/v1/chat/completions")
                        .header(OrvixHeaders.API_KEY, "orv_live_validkey")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").value("Prompt Injection Blocked"));
    }

    @Test
    void shouldReplayCachedResponseOnIdempotencyKeyHit() throws Exception {
        var tenant = new AuthAndQuotaService.TenantContext("ten_1", "Test Tenant", 60, 10000, "ACTIVE");
        when(authAndQuotaService.authenticate("orv_live_validkey")).thenReturn(tenant);
        doNothing().when(authAndQuotaService).checkQuota("ten_1");

        var cachedResponse = new com.orvix.common.contract.dto.ChatCompletionResponse(
                "idemp_test_key",
                "cached-provider",
                "gemini-1.5-flash",
                "Cached idempotency replay response",
                new com.orvix.common.contract.dto.ChatCompletionResponse.GuardrailSummary("CLEAN", 0),
                new com.orvix.common.contract.dto.ChatCompletionResponse.UsageSummary(10, 20, 30, 50L),
                5L
        );
        when(idempotencyService.getCachedResponse("ten_1", "idemp_test_key")).thenReturn(cachedResponse);

        mockMvc.perform(post("/v1/chat/completions")
                        .header(OrvixHeaders.API_KEY, "orv_live_validkey")
                        .header(OrvixHeaders.IDEMPOTENCY_KEY, "idemp_test_key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest)))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Idempotency-Replayed", "true"))
                .andExpect(jsonPath("$.id").value("idemp_test_key"))
                .andExpect(jsonPath("$.content").value("Cached idempotency replay response"));

        // Verify LLM router was NEVER dispatched and usage was NEVER published
        verify(llmRouter, never()).dispatch(anyString(), anyString());
        verify(usageEventPublisher, never()).publishUsageAsync(any());
    }
}
