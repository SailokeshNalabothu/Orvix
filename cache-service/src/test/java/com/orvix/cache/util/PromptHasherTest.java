package com.orvix.cache.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PromptHasherTest {

    @Test
    void shouldNormalizeAndHashConsistently() {
        String p1 = "  Explain   Idempotency  in Kafka   ";
        String p2 = "explain idempotency in kafka";

        String hash1 = PromptHasher.sha256Hex(p1);
        String hash2 = PromptHasher.sha256Hex(p2);

        assertNotNull(hash1);
        assertEquals(64, hash1.length());
        assertEquals(hash1, hash2);
    }

    @Test
    void shouldProduceDifferentHashesForDifferentPrompts() {
        String h1 = PromptHasher.sha256Hex("What is CAP theorem?");
        String h2 = PromptHasher.sha256Hex("What is PACELC theorem?");

        assertNotEquals(h1, h2);
    }
}
