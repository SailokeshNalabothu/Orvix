package com.orvix.gateway.client;

import com.orvix.common.contract.dto.CacheLookupRequest;
import com.orvix.common.contract.dto.CacheLookupResponse;
import com.orvix.common.contract.dto.CacheStoreRequest;
import com.orvix.common.contract.dto.CacheStoreResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

@Component
@Slf4j
public class SemanticCacheClient {

    private final RestTemplate restTemplate;
    private final String lookupUrl;
    private final String storeUrl;

    public SemanticCacheClient(
            RestTemplateBuilder builder,
            @Value("${orvix.services.cache-url:http://localhost:8082}") String cacheBaseUrl
    ) {
        this.restTemplate = builder
                .setConnectTimeout(Duration.ofMillis(100))
                .setReadTimeout(Duration.ofMillis(100))
                .build();
        this.lookupUrl = cacheBaseUrl + "/internal/v1/lookup";
        this.storeUrl = cacheBaseUrl + "/internal/v1/store";
    }

    /**
     * Looks up semantic cache. Fail-Open policy: on timeout (>100ms) or error, returns MISS/BYPASS.
     */
    public CacheLookupResponse lookup(String tenantId, String model, String prompt) {
        CacheLookupRequest request = new CacheLookupRequest(tenantId, model, prompt);
        try {
            ResponseEntity<CacheLookupResponse> response = restTemplate.postForEntity(
                    lookupUrl, request, CacheLookupResponse.class
            );
            return response.getBody() != null ? response.getBody() : CacheLookupResponse.miss();
        } catch (Exception ex) {
            log.warn("Semantic cache service degraded/timed out (>100ms). Fail-Open: routing to LLM. Error: {}", ex.getMessage());
            return new CacheLookupResponse(false, null, 0.0, "BYPASS");
        }
    }

    /**
     * Fire-and-forget asynchronous cache write.
     */
    public void storeAsync(String tenantId, String model, String prompt, String responseText) {
        CompletableFuture.runAsync(() -> {
            try {
                CacheStoreRequest request = new CacheStoreRequest(tenantId, model, prompt, responseText, 86400L);
                restTemplate.postForEntity(storeUrl, request, CacheStoreResponse.class);
                log.debug("Asynchronously cached LLM response for tenant: {}", tenantId);
            } catch (Exception ex) {
                log.warn("Fire-and-forget cache write failed: {}", ex.getMessage());
            }
        });
    }
}
