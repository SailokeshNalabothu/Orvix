package com.orvix.cache.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Cache Eligibility Rule Evaluator.
 *
 * Enforces business & system rules on whether a prompt is eligible for semantic caching.
 * Prompts are marked INELIGIBLE (and bypass cache lookup/storage) if they contain:
 * 1. Real-time dynamic temporal queries (weather, time, stock prices, live scores).
 * 2. Non-deterministic generation instructions (random numbers, UUIDs, dice rolls).
 * 3. Session-specific conversational state (session_id, user personal context).
 * 4. Ultra-short ambiguous phrases (< 10 chars).
 */
@Component
@Slf4j
public class CacheEligibilityEvaluator {

    public record EligibilityResult(boolean isEligible, String reason) {
        public static EligibilityResult eligible() {
            return new EligibilityResult(true, "ELIGIBLE");
        }

        public static EligibilityResult ineligible(String reason) {
            return new EligibilityResult(false, reason);
        }
    }

    private static final Pattern REALTIME_TEMPORAL_PATTERN = Pattern.compile(
            "(?i)\\b(?:what\\s+time\\s+is\\s+it|current\\s+time|todays?\\s+date|what\\s+day\\s+is\\s+it)\\b|" +
            "(?i)\\b(?:current|today's|latest|right\\s+now|live)\\s+(?:weather|temperature|stock|price|news|score|exchange\\s+rate|forex)\\b"
    );

    private static final Pattern NON_DETERMINISTIC_PATTERN = Pattern.compile(
            "(?i)\\b(?:random\\s+(?:number|uuid|string|guid|token)|flip\\s+a\\s+coin|roll\\s+a\\s+dice?|tell\\s+me\\s+a\\s+new\\s+joke|surprise\\s+me)\\b"
    );

    private static final Pattern SESSION_STATEFUL_PATTERN = Pattern.compile(
            "(?i)\\b(?:session_id|conversation_id|continue\\s+from\\s+before|as\\s+i\\s+said\\s+earlier|my\\s+name\\s+is)\\b"
    );

    public EligibilityResult evaluate(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return EligibilityResult.ineligible("EMPTY_PROMPT");
        }

        String trimmed = prompt.trim();
        if (trimmed.length() < 10) {
            return EligibilityResult.ineligible("LOW_INFORMATION_QUERY (length < 10)");
        }

        if (REALTIME_TEMPORAL_PATTERN.matcher(trimmed).find()) {
            return EligibilityResult.ineligible("REALTIME_DYNAMIC_QUERY");
        }

        if (NON_DETERMINISTIC_PATTERN.matcher(trimmed).find()) {
            return EligibilityResult.ineligible("NON_DETERMINISTIC_GENERATION");
        }

        if (SESSION_STATEFUL_PATTERN.matcher(trimmed).find()) {
            return EligibilityResult.ineligible("STATEFUL_SESSION_CONTEXT");
        }

        return EligibilityResult.eligible();
    }

    public boolean isEligible(String prompt) {
        return evaluate(prompt).isEligible();
    }
}
