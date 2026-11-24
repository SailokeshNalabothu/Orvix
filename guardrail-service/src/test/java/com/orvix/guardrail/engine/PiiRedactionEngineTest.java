package com.orvix.guardrail.engine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PiiRedactionEngineTest {

    private PiiRedactionEngine engine;

    @BeforeEach
    void setUp() {
        engine = new PiiRedactionEngine();
    }

    @Test
    void shouldLeaveCleanPromptUntouched() {
        String input = "Please summarize the financial results for Q3 2026.";
        var result = engine.redact(input);

        assertEquals(input, result.sanitizedText());
        assertEquals(0, result.redactionsCount());
        assertTrue(result.violations().isEmpty());
    }

    @Test
    void shouldRedactEmailAddresses() {
        String input = "Contact John at john.doe@example.com or support@company.org for details.";
        var result = engine.redact(input);

        assertEquals("Contact John at [REDACTED_EMAIL] or [REDACTED_EMAIL] for details.", result.sanitizedText());
        assertEquals(2, result.redactionsCount());
        assertTrue(result.violations().contains("PII_EMAIL"));
    }

    @Test
    void shouldRedactUsSsn() {
        String input = "My SSN is 123-45-6789. Please verify.";
        var result = engine.redact(input);

        assertEquals("My SSN is [REDACTED_SSN]. Please verify.", result.sanitizedText());
        assertEquals(1, result.redactionsCount());
        assertTrue(result.violations().contains("PII_SSN"));
    }

    @Test
    void shouldRedactIndianAadhaar() {
        String input = "Aadhaar number: 2345 6789 0123 for KYC.";
        var result = engine.redact(input);

        assertEquals("Aadhaar number: [REDACTED_AADHAAR] for KYC.", result.sanitizedText());
        assertEquals(1, result.redactionsCount());
        assertTrue(result.violations().contains("PII_AADHAAR"));
    }

    @Test
    void shouldRedactKnownApiKeys() {
        String input = "Use key sk-abcdef12345678901234567890 or ghp_123456789012345678901234567890123456 to call API.";
        var result = engine.redact(input);

        assertEquals("Use key [REDACTED_API_KEY] or [REDACTED_API_KEY] to call API.", result.sanitizedText());
        assertEquals(2, result.redactionsCount());
        assertTrue(result.violations().contains("SECRET_API_KEY"));
    }

    @Test
    void shouldRedactValidCreditCardsUsingLuhn() {
        // Valid Luhn test card (4532015112830366)
        String input = "Charge my Visa 4532-0151-1283-0366 with 50 dollars.";
        var result = engine.redact(input);

        assertEquals("Charge my Visa [REDACTED_CREDIT_CARD] with 50 dollars.", result.sanitizedText());
        assertEquals(1, result.redactionsCount());
        assertTrue(result.violations().contains("PII_CREDIT_CARD"));
    }

    @Test
    void shouldNotRedactInvalidCreditCardNumbers() {
        // 16 digits that fail Luhn checksum
        String input = "Tracking number 1234-5678-9012-3456 in invoice.";
        var result = engine.redact(input);

        // Not a valid CC, so it shouldn't be tagged as PII_CREDIT_CARD
        assertFalse(result.violations().contains("PII_CREDIT_CARD"));
    }
}
