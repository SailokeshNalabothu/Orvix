package com.orvix.cache.service;

import com.orvix.cache.model.SemanticCacheEntry;
import com.orvix.cache.repository.SemanticCacheRepository;
import com.orvix.common.contract.dto.CacheLookupRequest;
import com.orvix.common.contract.dto.CacheLookupResponse;
import com.orvix.common.contract.dto.CacheStoreRequest;
import com.orvix.common.contract.dto.CacheStoreResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SemanticCacheServiceTest {

    @Mock
    private SemanticCacheRepository cacheRepository;

    private EmbeddingService embeddingService;
    private ContextualMatchVerifier contextualVerifier;
    private CacheEligibilityEvaluator eligibilityEvaluator;
    private SemanticCacheService cacheService;

    @BeforeEach
    void setUp() {
        embeddingService = new EmbeddingService();
        contextualVerifier = new ContextualMatchVerifier();
        eligibilityEvaluator = new CacheEligibilityEvaluator();
        cacheService = new SemanticCacheService(cacheRepository, embeddingService, contextualVerifier, eligibilityEvaluator);
    }

    @Test
    void shouldReturnExactMatchHit() {
        CacheLookupRequest request = new CacheLookupRequest("tenant-1", "gpt-4o-mini", "What is CAP theorem?");
        SemanticCacheEntry cachedEntry = SemanticCacheEntry.builder()
                .id(101L)
                .tenantId("tenant-1")
                .model("gpt-4o-mini")
                .promptText("What is CAP theorem?")
                .responseText("CAP theorem states that a distributed system can only provide two of Consistency, Availability, Partition tolerance.")
                .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS))
                .build();

        when(cacheRepository.findByTenantIdAndModelAndPromptHash(eq("tenant-1"), eq("gpt-4o-mini"), anyString()))
                .thenReturn(Optional.of(cachedEntry));

        CacheLookupResponse response = cacheService.lookup(request);

        assertTrue(response.hit());
        assertEquals("HIT", response.cacheStatus());
        assertEquals(cachedEntry.getResponseText(), response.cachedResponse());
        assertEquals(1.0, response.similarity());
    }

    @Test
    void shouldReturnSemanticHnswMatchHitWhenExactMissesAndContextMatches() {
        CacheLookupRequest request = new CacheLookupRequest("tenant-1", "gpt-4o-mini", "Explain the CAP theorem");
        SemanticCacheEntry semanticEntry = SemanticCacheEntry.builder()
                .id(102L)
                .tenantId("tenant-1")
                .model("gpt-4o-mini")
                .promptText("What is CAP theorem?")
                .responseText("Cached answer about CAP theorem.")
                .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS))
                .build();

        when(cacheRepository.findByTenantIdAndModelAndPromptHash(eq("tenant-1"), eq("gpt-4o-mini"), anyString()))
                .thenReturn(Optional.empty());
        when(cacheRepository.findNearestSemanticMatch(eq("tenant-1"), eq("gpt-4o-mini"), anyString(), anyDouble(), any(Instant.class)))
                .thenReturn(Optional.of(semanticEntry));

        CacheLookupResponse response = cacheService.lookup(request);

        assertTrue(response.hit());
        assertEquals("HIT", response.cacheStatus());
        assertEquals(semanticEntry.getResponseText(), response.cachedResponse());
        assertTrue(response.similarity() >= 0.94);
    }

    @Test
    void shouldRejectSemanticMatchWhenTemporalYearsDiverge() {
        // Query: "What is the tax rate in India?"
        // Cached candidate: "What was the tax rate in India in 2020?"
        CacheLookupRequest request = new CacheLookupRequest("tenant-1", "gpt-4o-mini", "What is the tax rate in India?");
        SemanticCacheEntry historicalEntry = SemanticCacheEntry.builder()
                .id(103L)
                .tenantId("tenant-1")
                .model("gpt-4o-mini")
                .promptText("What was the tax rate in India in 2020?")
                .responseText("In 2020, India had tax slabs under the previous regime...")
                .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS))
                .build();

        when(cacheRepository.findByTenantIdAndModelAndPromptHash(anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(cacheRepository.findNearestSemanticMatch(eq("tenant-1"), eq("gpt-4o-mini"), anyString(), anyDouble(), any(Instant.class)))
                .thenReturn(Optional.of(historicalEntry));

        CacheLookupResponse response = cacheService.lookup(request);

        // Even though vector similarity is >= 0.94, contextual verifier detects TEMPORAL_YEAR_DIVERGENCE
        assertFalse(response.hit(), "Temporal year mismatch must reject semantic cache hit");
        assertEquals("MISS", response.cacheStatus());
        assertNull(response.cachedResponse());
    }

    @Test
    void shouldRejectSemanticMatchWhenPolarityDiverges() {
        // Query: "How to enable CORS in Spring Boot"
        // Cached candidate: "How to disable CORS in Spring Boot"
        CacheLookupRequest request = new CacheLookupRequest("tenant-1", "gpt-4o-mini", "How to enable CORS in Spring Boot");
        SemanticCacheEntry disableCorsEntry = SemanticCacheEntry.builder()
                .id(104L)
                .tenantId("tenant-1")
                .model("gpt-4o-mini")
                .promptText("How to disable CORS in Spring Boot")
                .responseText("To disable CORS...")
                .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS))
                .build();

        when(cacheRepository.findByTenantIdAndModelAndPromptHash(anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(cacheRepository.findNearestSemanticMatch(eq("tenant-1"), eq("gpt-4o-mini"), anyString(), anyDouble(), any(Instant.class)))
                .thenReturn(Optional.of(disableCorsEntry));

        CacheLookupResponse response = cacheService.lookup(request);

        assertFalse(response.hit(), "Opposite polarity (enable vs disable) must reject semantic cache hit");
        assertEquals("MISS", response.cacheStatus());
    }

    @Test
    void shouldBypassCacheWhenPromptIsIneligible() {
        // Dynamic time query
        CacheLookupRequest request = new CacheLookupRequest("tenant-1", "gpt-4o-mini", "What time is it right now?");

        CacheLookupResponse response = cacheService.lookup(request);

        assertFalse(response.hit());
        assertEquals("INELIGIBLE", response.cacheStatus());
        assertNull(response.cachedResponse());
        verify(cacheRepository, never()).findByTenantIdAndModelAndPromptHash(any(), any(), any());
    }

    @Test
    void shouldIsolateTenantsSoTenantBCannotAccessTenantA() {
        CacheLookupRequest requestTenantB = new CacheLookupRequest("tenant-B", "gpt-4o-mini", "What is CAP theorem?");

        when(cacheRepository.findByTenantIdAndModelAndPromptHash(eq("tenant-B"), eq("gpt-4o-mini"), anyString()))
                .thenReturn(Optional.empty());
        when(cacheRepository.findNearestSemanticMatch(eq("tenant-B"), anyString(), anyString(), anyDouble(), any(Instant.class)))
                .thenReturn(Optional.empty());

        CacheLookupResponse response = cacheService.lookup(requestTenantB);

        assertFalse(response.hit());
        assertEquals("MISS", response.cacheStatus());
        verify(cacheRepository).findByTenantIdAndModelAndPromptHash(eq("tenant-B"), any(), any());
        verify(cacheRepository).findNearestSemanticMatch(eq("tenant-B"), any(), any(), anyDouble(), any());
    }

    @Test
    void shouldIsolateModelsSoReasoningModelDoesNotHitFastModelCache() {
        CacheLookupRequest requestPro = new CacheLookupRequest("tenant-1", "gemini-1.5-pro", "Explain Paxos consensus");

        when(cacheRepository.findByTenantIdAndModelAndPromptHash(eq("tenant-1"), eq("gemini-1.5-pro"), anyString()))
                .thenReturn(Optional.empty());
        when(cacheRepository.findNearestSemanticMatch(eq("tenant-1"), eq("gemini-1.5-pro"), anyString(), anyDouble(), any(Instant.class)))
                .thenReturn(Optional.empty());

        CacheLookupResponse response = cacheService.lookup(requestPro);

        assertFalse(response.hit());
        verify(cacheRepository).findByTenantIdAndModelAndPromptHash(any(), eq("gemini-1.5-pro"), any());
        verify(cacheRepository).findNearestSemanticMatch(any(), eq("gemini-1.5-pro"), any(), anyDouble(), any());
    }

    @Test
    void shouldStoreValidEligiblePrompt() {
        CacheStoreRequest request = new CacheStoreRequest("tenant-1", "gpt-4o-mini", "What is Raft?", "Raft is consensus.", 3600L);
        SemanticCacheEntry saved = SemanticCacheEntry.builder().id(201L).tenantId("tenant-1").build();

        when(cacheRepository.save(any(SemanticCacheEntry.class))).thenReturn(saved);

        CacheStoreResponse response = cacheService.store(request);

        assertTrue(response.stored());
        assertEquals(201L, response.cacheId());
    }

    @Test
    void shouldSkipStoringIneligibleDynamicPrompt() {
        CacheStoreRequest request = new CacheStoreRequest("tenant-1", "gpt-4o-mini", "What time is it?", "It is 3 PM", 3600L);

        CacheStoreResponse response = cacheService.store(request);

        assertFalse(response.stored());
        assertEquals(-1L, response.cacheId());
        verify(cacheRepository, never()).save(any());
    }
}
