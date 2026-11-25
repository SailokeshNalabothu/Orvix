package com.orvix.cache.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CacheEligibilityEvaluatorTest {

    private CacheEligibilityEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new CacheEligibilityEvaluator();
    }

    @Test
    @DisplayName("Eligible: Stable algorithmic and technical queries")
    void shouldAllowStableTechnicalQueries() {
        assertTrue(evaluator.isEligible("Explain how the Raft consensus algorithm maintains state machine consistency."));
        assertTrue(evaluator.isEligible("What is the difference between optimistic and pessimistic locking?"));
        assertTrue(evaluator.isEligible("Write an idempotent SQL migration script in PostgreSQL."));
    }

    @Test
    @DisplayName("Ineligible: Real-time dynamic temporal queries")
    void shouldRejectRealtimeDynamicQueries() {
        assertFalse(evaluator.isEligible("What time is it right now?"));
        assertFalse(evaluator.isEligible("What is the current weather in Tokyo?"));
        assertFalse(evaluator.isEligible("What is today's stock price of Google?"));
        assertFalse(evaluator.isEligible("Give me the latest news on technology."));
    }

    @Test
    @DisplayName("Ineligible: Non-deterministic generation directives")
    void shouldRejectNonDeterministicDirectives() {
        assertFalse(evaluator.isEligible("Generate a random uuid for my session."));
        assertFalse(evaluator.isEligible("Flip a coin and tell me heads or tails."));
        assertFalse(evaluator.isEligible("Roll a dice and output the result."));
        assertFalse(evaluator.isEligible("Tell me a new joke I haven't heard."));
    }

    @Test
    @DisplayName("Ineligible: Session-specific stateful context")
    void shouldRejectStatefulSessionContext() {
        assertFalse(evaluator.isEligible("My session_id=abc-123, continue our discussion."));
        assertFalse(evaluator.isEligible("My name is John, remember this for later."));
    }

    @Test
    @DisplayName("Ineligible: Ultra-short ambiguous queries (< 10 chars)")
    void shouldRejectUltraShortQueries() {
        assertFalse(evaluator.isEligible("hi"));
        assertFalse(evaluator.isEligible("ok"));
        assertFalse(evaluator.isEligible("thanks"));
        assertFalse(evaluator.isEligible(""));
    }
}
