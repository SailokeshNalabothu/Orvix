package com.orvix.cache.service;

import com.orvix.cache.model.SemanticCacheEntry;
import com.orvix.cache.repository.SemanticCacheRepository;
import com.orvix.cache.util.PromptHasher;
import com.orvix.common.contract.dto.CacheLookupRequest;
import com.orvix.common.contract.dto.CacheLookupResponse;
import com.orvix.common.contract.dto.CacheStoreRequest;
import com.orvix.common.contract.dto.CacheStoreResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class SemanticCacheService {

    public static final double SIMILARITY_THRESHOLD = 0.94;
    public static final double MAX_COSINE_DISTANCE = 1.0 - SIMILARITY_THRESHOLD; // 0.06

    private final SemanticCacheRepository cacheRepository;
    private final EmbeddingService embeddingService;

    @Transactional(readOnly = true)
    public CacheLookupResponse lookup(CacheLookupRequest request) {
        Instant now = Instant.now();
        String promptHash = PromptHasher.sha256Hex(request.prompt());

        // 1. Fast Path: Exact Hash Match
        Optional<SemanticCacheEntry> exactMatch = cacheRepository.findByTenantIdAndModelAndPromptHash(
                request.tenantId(), request.model(), promptHash
        );

        if (exactMatch.isPresent() && exactMatch.get().getExpiresAt().isAfter(now)) {
            log.debug("Semantic cache exact hit for tenant: {}, model: {}", request.tenantId(), request.model());
            return CacheLookupResponse.hit(exactMatch.get().getResponseText(), 1.0);
        }

        // 2. Semantic HNSW Cosine Search (pgvector <=> distance <= 0.06)
        float[] queryEmbedding = embeddingService.generateEmbedding(request.prompt());
        String vectorString = embeddingService.toPgVectorString(queryEmbedding);

        Optional<SemanticCacheEntry> nearestMatch = cacheRepository.findNearestSemanticMatch(
                request.tenantId(), request.model(), vectorString, MAX_COSINE_DISTANCE, now
        );

        if (nearestMatch.isPresent()) {
            log.info("Semantic cache hit (>= 0.94) for tenant: {}, model: {}", request.tenantId(), request.model());
            return CacheLookupResponse.hit(nearestMatch.get().getResponseText(), 0.95);
        }

        log.debug("Semantic cache miss for tenant: {}, model: {}", request.tenantId(), request.model());
        return CacheLookupResponse.miss();
    }

    @Transactional
    public CacheStoreResponse store(CacheStoreRequest request) {
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(request.getEffectiveTtlSeconds());
        String promptHash = PromptHasher.sha256Hex(request.prompt());

        float[] embedding = embeddingService.generateEmbedding(request.prompt());
        String vectorString = embeddingService.toPgVectorString(embedding);

        SemanticCacheEntry entry = SemanticCacheEntry.builder()
                .tenantId(request.tenantId())
                .model(request.model())
                .promptHash(promptHash)
                .promptText(request.prompt())
                .embedding(vectorString)
                .responseText(request.responseText())
                .expiresAt(expiresAt)
                .createdAt(now)
                .build();

        SemanticCacheEntry saved = cacheRepository.save(entry);
        log.info("Stored semantic cache entry ID: {} for tenant: {}, expiresAt: {}",
                saved.getId(), saved.getTenantId(), saved.getExpiresAt());

        return new CacheStoreResponse(true, saved.getId());
    }
}
