package com.orvix.cache.service;

import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

@Service
public class EmbeddingService {

    public static final int VECTOR_DIMENSION = 768;

    /**
     * Generates a 768-dimensional normalized semantic embedding vector.
     * Tokens are projected onto dense subspace using subword hashing and normalized with L2 unit norm
     * to ensure pgvector cosine distance: distance = 1 - cosine_similarity.
     */
    public float[] generateEmbedding(String text) {
        float[] vector = new float[VECTOR_DIMENSION];
        if (text == null || text.isBlank()) {
            return vector;
        }

        String normalized = text.toLowerCase(Locale.ROOT).trim();
        String[] words = normalized.split("\\s+");

        for (String word : words) {
            int wordHash = Math.abs(word.hashCode());
            // Word level contribution
            int primaryIdx = wordHash % VECTOR_DIMENSION;
            vector[primaryIdx] += 1.0f;

            // Character tri-gram contribution to capture semantic / morphological similarity
            if (word.length() >= 3) {
                for (int i = 0; i <= word.length() - 3; i++) {
                    String trigram = word.substring(i, i + 3);
                    int triHash = Math.abs(trigram.hashCode() ^ 0x5bd1e995);
                    int triIdx = triHash % VECTOR_DIMENSION;
                    vector[triIdx] += 0.5f;
                }
            }
        }

        // Apply L2 Normalization so dot product equals cosine similarity
        double sumSq = 0.0;
        for (float v : vector) {
            sumSq += (double) v * v;
        }

        double norm = Math.sqrt(sumSq);
        if (norm > 1e-9) {
            for (int i = 0; i < VECTOR_DIMENSION; i++) {
                vector[i] = (float) (vector[i] / norm);
            }
        }

        return vector;
    }

    /**
     * Converts float array to pgvector string format: "[v0,v1,v2,...,v767]"
     */
    public String toPgVectorString(float[] vector) {
        StringBuilder sb = new StringBuilder(VECTOR_DIMENSION * 10);
        sb.append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(String.format(Locale.ROOT, "%.6f", vector[i]));
        }
        sb.append(']');
        return sb.toString();
    }
}
