package com.orvix.billing.service;

import com.orvix.common.contract.event.UsageRecordedEvent;
import org.springframework.stereotype.Component;

@Component
public class CostCalculator {

    // 1 USD = 1,000,000 micro-dollars
    public static final long CACHE_HIT_FEE_MICROS = 50L; // $0.00005 per cache hit

    public long calculateCostMicros(UsageRecordedEvent event) {
        if (event.cache_hit()) {
            return CACHE_HIT_FEE_MICROS;
        }

        long promptRatePer1k;
        long completionRatePer1k;

        String model = event.model() != null ? event.model().toLowerCase() : "";
        if (model.contains("flash") || model.contains("mini")) {
            promptRatePer1k = 150L;      // $0.15 / 1M tokens
            completionRatePer1k = 600L;  // $0.60 / 1M tokens
        } else {
            promptRatePer1k = 2500L;     // $2.50 / 1M tokens
            completionRatePer1k = 10000L; // $10.00 / 1M tokens
        }

        long promptCost = (long) Math.ceil((event.prompt_tokens() * promptRatePer1k) / 1000.0);
        long completionCost = (long) Math.ceil((event.completion_tokens() * completionRatePer1k) / 1000.0);
        long total = promptCost + completionCost;

        return Math.max(1L, total);
    }
}
