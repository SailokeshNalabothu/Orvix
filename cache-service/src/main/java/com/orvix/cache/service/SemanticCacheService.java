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
    private final ContextualMatchVerifier contextualVerifier;
    private final CacheEligibilityEvaluator eligibilityEvaluator;

    @Transactional(readOnly = true)
    public CacheLookupResponse lookup(CacheLookupRequest request) {
        // 0. Cache Eligibility Rules Check
        var eligibility = eligibilityEvaluator.evaluate(request.prompt());
        if (!eligibility.isEligible()) {
            log.debug("Cache lookup bypassed: prompt ineligible [reason={}] for tenant: {}",
                    eligibility.reason(), request.tenantId());
            return CacheLookupResponse.ineligible(eligibility.reason());
        }

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
            SemanticCacheEntry candidate = nearestMatch.get();
            // 3. Post-Vector Contextual & Temporal Equivalence Verification
            var contextCheck = contextualVerifier.verify(request.prompt(), candidate.getPromptText());
            if (contextCheck.matched()) {
                log.info("Semantic cache verified hit (>= 0.94) for tenant: {}, model: {}",
                        request.tenantId(), request.model());
                return CacheLookupResponse.hit(candidate.getResponseText(), 0.95);
            } else {
                log.warn("Semantic vector match rejected by ContextualVerifier [reason={}] for tenant: {}, query='{}', cached='{}'",
                        contextCheck.reason(), request.tenantId(), request.prompt(), candidate.getPromptText());
            }
        }

        log.debug("Semantic cache miss for tenant: {}, model: {}", request.tenantId(), request.model());
        return CacheLookupResponse.miss();
    }

    @Transactional
    public CacheStoreResponse store(CacheStoreRequest request) {
        // 0. Eligibility Check: Do not cache ineligible / dynamic / stateful prompts
        var eligibility = eligibilityEvaluator.evaluate(request.prompt());
        if (!eligibility.isEligible()) {
            log.debug("Cache store skipped: prompt ineligible [reason={}] for tenant: {}",
                    eligibility.reason(), request.tenantId());
            return new CacheStoreResponse(false, -1L);
        }

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
        log.info("Stored semantic cache entry ID: {} for tenant: {}, model: {}, expiresAt: {}",
                saved.getId(), saved.getTenantId(), saved.getModel(), saved.getExpiresAt());

        return new CacheStoreResponse(true, saved.getId());
    }
}
