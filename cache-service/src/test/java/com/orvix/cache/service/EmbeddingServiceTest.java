package com.orvix.cache.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EmbeddingServiceTest {

    private EmbeddingService embeddingService;

    @BeforeEach
    void setUp() {
        embeddingService = new EmbeddingService();
    }

    @Test
    void shouldGenerate768DimensionalNormalizedVector() {
        float[] vector = embeddingService.generateEmbedding("Explain double-entry billing in distributed systems");

        assertEquals(EmbeddingService.VECTOR_DIMENSION, vector.length);

        // Check L2 unit norm
        double sumSq = 0.0;
        for (float v : vector) {
            sumSq += (double) v * v;
        }
        assertEquals(1.0, Math.sqrt(sumSq), 0.001, "Embedding vector must have unit L2 norm");
    }

    @Test
    void shouldFormatCorrectPgVectorString() {
        float[] vector = embeddingService.generateEmbedding("Hello world");
        String pgVector = embeddingService.toPgVectorString(vector);

        assertNotNull(pgVector);
        assertTrue(pgVector.startsWith("["));
        assertTrue(pgVector.endsWith("]"));
        String[] components = pgVector.substring(1, pgVector.length() - 1).split(",");
        assertEquals(768, components.length);
    }
}
