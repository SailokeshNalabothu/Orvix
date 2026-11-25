package com.orvix.gateway.controller;

import com.orvix.common.contract.dto.CacheLookupResponse;
import com.orvix.common.contract.dto.ChatCompletionRequest;
import com.orvix.common.contract.dto.ChatCompletionResponse;
import com.orvix.common.contract.dto.PromptInspectionResponse;
import com.orvix.common.contract.event.UsageRecordedEvent;
import com.orvix.common.contract.header.CacheStatus;
import com.orvix.common.contract.header.OrvixHeaders;
import com.orvix.gateway.auth.AuthAndQuotaService;
import com.orvix.gateway.client.GuardrailClient;
import com.orvix.gateway.client.SemanticCacheClient;
import com.orvix.gateway.event.UsageEventPublisher;
import com.orvix.gateway.ratelimit.RedisRateLimiter;
import com.orvix.gateway.router.LlmRouter;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;
import java.util.stream.Collectors;

@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
@Slf4j
public class GatewayController {

    private final AuthAndQuotaService authAndQuotaService;
    private final RedisRateLimiter rateLimiter;
    private final GuardrailClient guardrailClient;
    private final SemanticCacheClient cacheClient;
    private final LlmRouter llmRouter;
    private final UsageEventPublisher usageEventPublisher;

    @PostMapping("/chat/completions")
    public ResponseEntity<ChatCompletionResponse> createChatCompletion(
            @RequestHeader(value = OrvixHeaders.API_KEY, required = false) String rawApiKey,
            @RequestHeader(value = OrvixHeaders.IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @Valid @RequestBody ChatCompletionRequest request,
            HttpServletResponse servletResponse
    ) {
        long startTimeMs = System.currentTimeMillis();
        String traceId = UUID.randomUUID().toString().replace("-", "");
        String requestId = idempotencyKey != null && !idempotencyKey.isBlank() ? idempotencyKey : "req_" + UUID.randomUUID();

        // 1. Auth & Quota Check (Fail Fast)
        var tenant = authAndQuotaService.authenticate(rawApiKey);
        authAndQuotaService.checkQuota(tenant.tenantId());

        // 2. Atomic Redis Token Bucket Rate Limiting (ADR-001)
        var rateLimitResult = rateLimiter.checkRateLimit(tenant.tenantId(), tenant.rpmLimit(), 1);
        if (!rateLimitResult.allowed()) {
            servletResponse.setHeader("Retry-After", String.valueOf(rateLimitResult.retryAfterSeconds()));
            throw new RateLimitExceededException(
                    "Tenant exceeded " + tenant.rpmLimit() + " RPM limit. Retry after " + rateLimitResult.retryAfterSeconds() + " seconds."
            );
        }

        // Aggregate prompt from messages
        String rawPrompt = request.messages().stream()
                .map(ChatCompletionRequest.ChatMessage::content)
                .collect(Collectors.joining("\n"));

        // 3. Zero-Trust Guardrail Inspection (Sync HTTP, Fail-Closed)
        PromptInspectionResponse guardrailResult = guardrailClient.inspect(traceId, tenant.tenantId(), rawPrompt);
        String promptToUse = guardrailResult.sanitizedPrompt() != null ? guardrailResult.sanitizedPrompt() : rawPrompt;

        // 4. Semantic Caching Layer (Sync HTTP, Fail-Open)
        boolean cacheEnabled = request.cache_enabled() == null || request.cache_enabled();
        CacheStatus cacheStatus = CacheStatus.MISS;
        String providerUsed;
        String completionContent;
        int promptTokens;
        int completionTokens;
        long estimatedCost;

        CacheLookupResponse cacheLookup = null;
        if (cacheEnabled) {
            cacheLookup = cacheClient.lookup(tenant.tenantId(), request.model(), promptToUse);
        }

        if (cacheLookup != null && cacheLookup.hit()) {
            cacheStatus = CacheStatus.HIT;
            providerUsed = "semantic-cache";
            completionContent = cacheLookup.cachedResponse();
            promptTokens = 0;
            completionTokens = 0;
            estimatedCost = 50L; // 50 micro-dollars cache hit fee
        } else {
            if (cacheLookup != null && "BYPASS".equals(cacheLookup.cacheStatus())) {
                cacheStatus = CacheStatus.BYPASS;
            }

            // 5. LLM Provider Dispatch with Resilience4j (Automatic Failover)
            var llmResult = llmRouter.dispatch(request.model(), promptToUse);
            providerUsed = llmResult.provider();
            completionContent = llmResult.content();
            promptTokens = llmResult.promptTokens();
            completionTokens = llmResult.completionTokens();
            estimatedCost = llmResult.estimatedCostMicros();

            // Fire-and-forget cache write on cache miss
            if (cacheEnabled) {
                cacheClient.storeAsync(tenant.tenantId(), request.model(), promptToUse, completionContent);
            }
        }

        long latencyMs = System.currentTimeMillis() - startTimeMs;

        // 6. Async Side Effects: Non-blocking Kafka usage emission
        UsageRecordedEvent usageEvent = new UsageRecordedEvent(
                "evt_" + UUID.randomUUID(),
                traceId,
                tenant.tenantId(),
                requestId,
                providerUsed,
                request.model(),
                cacheStatus == CacheStatus.HIT,
                promptTokens,
                completionTokens,
                Instant.now()
        );
        usageEventPublisher.publishUsageAsync(usageEvent);

        // 7. Set Standard Response Headers
        servletResponse.setHeader(OrvixHeaders.TRACE_ID, traceId);
        servletResponse.setHeader(OrvixHeaders.CACHE_STATUS, cacheStatus.name());
        servletResponse.setHeader(OrvixHeaders.PROVIDER_USED, providerUsed);

        // 8. Build JSON Response
        ChatCompletionResponse response = new ChatCompletionResponse(
                requestId,
                providerUsed,
                request.model(),
                completionContent,
                new ChatCompletionResponse.GuardrailSummary(
                        guardrailResult.status(),
                        guardrailResult.redactionsCount()
                ),
                new ChatCompletionResponse.UsageSummary(
                        promptTokens,
                        completionTokens,
                        promptTokens + completionTokens,
                        estimatedCost
                ),
                latencyMs
        );

        return ResponseEntity.ok(response);
    }

    public static class RateLimitExceededException extends RuntimeException {
        public RateLimitExceededException(String message) {
            super(message);
        }
    }

    @GetMapping("/system/mesh")
    public ResponseEntity<java.util.Map<String, String>> getSystemMesh() {
        java.util.Map<String, String> mesh = new java.util.LinkedHashMap<>();
        mesh.put("gateway", "UP");
        mesh.put("guardrail", isPortListening("localhost", 8081) ? "UP" : "DOWN");
        mesh.put("cache", isPortListening("localhost", 8082) ? "UP" : "DOWN");
        mesh.put("billing", isPortListening("localhost", 8083) ? "UP" : "DOWN");
        return ResponseEntity.ok(mesh);
    }

    private boolean isPortListening(String host, int port) {
        try (var socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress(host, port), 200);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
