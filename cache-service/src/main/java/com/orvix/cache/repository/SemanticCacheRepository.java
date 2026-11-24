package com.orvix.cache.repository;

import com.orvix.cache.model.SemanticCacheEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface SemanticCacheRepository extends JpaRepository<SemanticCacheEntry, Long> {

    Optional<SemanticCacheEntry> findByTenantIdAndModelAndPromptHash(String tenantId, String model, String promptHash);

    /**
     * Finds the nearest semantic match using cosine distance (<=> operator in pgvector).
     * Cosine distance = 1 - cosine_similarity.
     * For cosine similarity >= 0.94, cosine distance must be <= 0.06.
     */
    @Query(value = """
        SELECT *
        FROM semantic_cache
        WHERE tenant_id = :tenantId
          AND model = :model
          AND expires_at > :now
          AND (embedding <=> cast(:embedding as vector)) <= :maxDistance
        ORDER BY embedding <=> cast(:embedding as vector) ASC
        LIMIT 1
        """, nativeQuery = true)
    Optional<SemanticCacheEntry> findNearestSemanticMatch(
            @Param("tenantId") String tenantId,
            @Param("model") String model,
            @Param("embedding") String embedding,
            @Param("maxDistance") double maxDistance,
            @Param("now") Instant now
    );
}
