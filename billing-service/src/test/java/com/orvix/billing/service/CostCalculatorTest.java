package com.orvix.billing.service;

import com.orvix.common.contract.event.UsageRecordedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class CostCalculatorTest {

    private CostCalculator calculator;

    @BeforeEach
    void setUp() {
        calculator = new CostCalculator();
    }

    @Test
    void shouldChargeFixedCacheHitFeeWhenCacheHitIsTrue() {
        UsageRecordedEvent event = new UsageRecordedEvent(
                "evt-1", "trace-1", "ten-1", "req-1", "cache", "gpt-4o-mini",
                true, 50, 100, Instant.now()
        );

        long cost = calculator.calculateCostMicros(event);

        assertEquals(CostCalculator.CACHE_HIT_FEE_MICROS, cost);
    }

    @Test
    void shouldCalculateCorrectMicroDollarsForMiniModel() {
        UsageRecordedEvent event = new UsageRecordedEvent(
                "evt-2", "trace-2", "ten-1", "req-2", "gemini-primary", "gpt-4o-mini",
                false, 14, 112, Instant.now()
        );

        long cost = calculator.calculateCostMicros(event);

        // prompt: ceil(14 * 150 / 1000) = ceil(2.1) = 3
        // completion: ceil(112 * 600 / 1000) = ceil(67.2) = 68
        // total: 71 micro-dollars
        assertEquals(71L, cost);
    }

    @Test
    void shouldGuaranteeMinimumOneMicroDollar() {
        UsageRecordedEvent event = new UsageRecordedEvent(
                "evt-3", "trace-3", "ten-1", "req-3", "gemini-primary", "gpt-4o-mini",
                false, 0, 0, Instant.now()
        );

        long cost = calculator.calculateCostMicros(event);

        assertEquals(1L, cost);
    }
}
