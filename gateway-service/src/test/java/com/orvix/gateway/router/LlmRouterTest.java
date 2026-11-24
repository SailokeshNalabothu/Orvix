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
        // When fallbackSecondaryProvider is called
        var fallbackResult = router.fallbackSecondaryProvider(
                "gpt-4o-mini", "Explain distributed caching",
                new LlmRouter.UpstreamLlmException("Primary 503")
        );

        assertEquals(LlmRouter.PROVIDER_OPENAI_FALLBACK, fallbackResult.provider());
        assertTrue(fallbackResult.content().contains("Fallback Provider"));
        assertTrue(fallbackResult.promptTokens() > 0);
    }
}
