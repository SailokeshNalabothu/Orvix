package com.orvix.gateway.router;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LlmRouterTest {

    private LlmRouter router;

    @BeforeEach
    void setUp() {
        router = new LlmRouter();
    }

    @Test
    void shouldDispatchToPrimaryProviderNormally() {
        var result = router.dispatch("gpt-4o-mini", "Explain distributed caching");

        assertEquals(LlmRouter.PROVIDER_GEMINI_PRIMARY, result.provider());
        assertNotNull(result.content());
        assertTrue(result.promptTokens() > 0);
        assertTrue(result.completionTokens() > 0);
    }

    @Test
    void shouldFailoverToSecondaryProviderOnPrimaryFailure() {
        var fallbackResult = router.fallbackSecondaryProvider(
                "gpt-4o-mini", "Explain distributed caching",
                new LlmRouter.UpstreamLlmException("Primary 503")
        );

        assertEquals(LlmRouter.PROVIDER_OPENAI_FALLBACK, fallbackResult.provider());
        assertTrue(fallbackResult.content().contains("Fallback Provider"));
        assertTrue(fallbackResult.promptTokens() > 0);
    }

    @Test
    void shouldClassifyLowComplexityPromptAsFastTier() {
        var analysis = router.analyzeComplexity("What is the capital of France?");
        assertEquals("FAST_TIER", analysis.complexityTier());
        assertEquals("gemini-1.5-flash", analysis.routedModel());
        assertTrue(analysis.complexityScore() < 0.40);
    }

    @Test
    void shouldClassifyComplexAlgorithmicPromptAsReasoningTier() {
        var prompt = "Implement a distributed consensus algorithm handling network partitions, deadlock, and Byzantine faults with virtual threads";
        var analysis = router.analyzeComplexity(prompt);
        assertEquals("REASONING_TIER", analysis.complexityTier());
        assertEquals("gemini-1.5-pro", analysis.routedModel());
        assertTrue(analysis.complexityScore() >= 0.40);
    }

    @Test
    void shouldAutoCascadeDynamicallyToOptimalModel() {
        var fastResult = router.dispatch("auto-cascade", "Hello, summarize this sentence.");
        assertEquals("gemini-1.5-flash", fastResult.routedModel());
        assertEquals("FAST_TIER", fastResult.complexityTier());

        var deepResult = router.dispatch("auto-cascade", "Refactor this distributed Raft state machine to eliminate deadlock and optimize concurrency: public void stepDown() {}");
        assertEquals("gemini-1.5-pro", deepResult.routedModel());
        assertEquals("REASONING_TIER", deepResult.complexityTier());
    }
}
