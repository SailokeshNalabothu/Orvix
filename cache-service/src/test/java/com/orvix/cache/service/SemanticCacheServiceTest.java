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
import org.mockito.ArgumentCaptor;
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
    private SemanticCacheService cacheService;

    @BeforeEach
    void setUp() {
        embeddingService = new EmbeddingService();
        cacheService = new SemanticCacheService(cacheRepository, embeddingService);
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
    void shouldReturnSemanticHnswMatchHitWhenExactMisses() {
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
    void shouldReturnMissWhenNoMatchFound() {
        CacheLookupRequest request = new CacheLookupRequest("tenant-1", "gpt-4o-mini", "Something brand new");

        when(cacheRepository.findByTenantIdAndModelAndPromptHash(eq("tenant-1"), eq("gpt-4o-mini"), anyString()))
                .thenReturn(Optional.empty());
        when(cacheRepository.findNearestSemanticMatch(anyString(), anyString(), anyString(), anyDouble(), any(Instant.class)))
                .thenReturn(Optional.empty());

        CacheLookupResponse response = cacheService.lookup(request);

        assertFalse(response.hit());
        assertEquals("MISS", response.cacheStatus());
        assertNull(response.cachedResponse());
    }

    @Test
    void shouldStoreCacheEntryWithVectorAndTtl() {
        CacheStoreRequest request = new CacheStoreRequest(
                "tenant-1", "gpt-4o-mini", "What is an event loop?", "An event loop is...", 3600L
        );
        SemanticCacheEntry saved = SemanticCacheEntry.builder()
                .id(999L)
                .tenantId("tenant-1")
                .build();
        when(cacheRepository.save(any())).thenReturn(saved);

        CacheStoreResponse response = cacheService.store(request);

        assertTrue(response.stored());
        assertEquals(999L, response.cacheId());

        ArgumentCaptor<SemanticCacheEntry> captor = ArgumentCaptor.forClass(SemanticCacheEntry.class);
        verify(cacheRepository).save(captor.capture());
        assertEquals("tenant-1", captor.getValue().getTenantId());
        assertEquals("gpt-4o-mini", captor.getValue().getModel());
        assertEquals("What is an event loop?", captor.getValue().getPromptText());
        assertNotNull(captor.getValue().getPromptHash());
        assertNotNull(captor.getValue().getEmbedding());
        assertTrue(captor.getValue().getEmbedding().startsWith("["));
    }
}
