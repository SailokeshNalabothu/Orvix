package com.orvix.cache.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ContextualMatchVerifierTest {

    private ContextualMatchVerifier verifier;

    @BeforeEach
    void setUp() {
        verifier = new ContextualMatchVerifier();
    }

    @Test
    @DisplayName("Reject cache hit when temporal year diverges (Current vs Historical 2020)")
    void shouldRejectWhenTemporalYearDiverges() {
        String query = "What is the tax rate in India?";
        String cached = "What was the tax rate in India in 2020?";

        ContextualMatchVerifier.VerificationResult result = verifier.verify(query, cached);

        assertFalse(result.matched(), "Must reject match when query is current and cached specifies year 2020");
        assertTrue(result.reason().contains("TEMPORAL_YEAR_DIVERGENCE"));
    }

    @Test
    @DisplayName("Reject cache hit when two historical years differ (2019 vs 2020)")
    void shouldRejectWhenHistoricalYearsDiffer() {
        String query = "What was the tax rate in India in 2019?";
        String cached = "What was the tax rate in India in 2020?";

        ContextualMatchVerifier.VerificationResult result = verifier.verify(query, cached);

        assertFalse(result.matched());
        assertTrue(result.reason().contains("TEMPORAL_YEAR_DIVERGENCE"));
    }

    @Test
    @DisplayName("Accept genuine semantic paraphrase with identical temporal context")
    void shouldAcceptGenuineSemanticParaphrase() {
        String query = "How does the Raft consensus protocol prevent split-brain inconsistencies?";
        String cached = "Explain how the Raft consensus algorithm maintains state machine consistency during network partitions.";

        ContextualMatchVerifier.VerificationResult result = verifier.verify(query, cached);

        assertTrue(result.matched());
        assertEquals("SEMANTIC_AND_CONTEXT_EQUIVALENT", result.reason());
    }

    @Test
    @DisplayName("Reject cache hit when polarity diverges (enable vs disable)")
    void shouldRejectWhenPolarityDiverges() {
        String query = "How to enable CORS in Spring Boot 3";
        String cached = "How to disable CORS in Spring Boot 3";

        ContextualMatchVerifier.VerificationResult result = verifier.verify(query, cached);

        assertFalse(result.matched());
        assertEquals("POLARITY_DIVERGENCE", result.reason());
    }

    @Test
    @DisplayName("Reject cache hit when tech stack diverges (Python vs Java)")
    void shouldRejectWhenTechStackDiverges() {
        String query = "How do I reverse a linked list in Python?";
        String cached = "How do I reverse a linked list in Java?";

        ContextualMatchVerifier.VerificationResult result = verifier.verify(query, cached);

        assertFalse(result.matched());
        assertTrue(result.reason().contains("TECH_STACK_DIVERGENCE"));
    }

    @Test
    @DisplayName("Reject cache hit when geographic region diverges (India vs USA)")
    void shouldRejectWhenGeographicRegionDiverges() {
        String query = "What is the corporate tax rate in India?";
        String cached = "What is the corporate tax rate in USA?";

        ContextualMatchVerifier.VerificationResult result = verifier.verify(query, cached);

        assertFalse(result.matched());
        assertTrue(result.reason().contains("JURISDICTION_DIVERGENCE"));
    }

    @Test
    @DisplayName("Reject cache hit when numeric parameter diverges (5 vs 10)")
    void shouldRejectWhenNumericParameterDiverges() {
        String query = "Top 5 sorting algorithms in computer science";
        String cached = "Top 10 sorting algorithms in computer science";

        ContextualMatchVerifier.VerificationResult result = verifier.verify(query, cached);

        assertFalse(result.matched());
        assertTrue(result.reason().contains("NUMERIC_PARAM_DIVERGENCE"));
    }
}
