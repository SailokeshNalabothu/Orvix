package com.orvix.cache.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Contextual & Semantic Equivalence Verifier.
 *
 * Prevents false semantic cache matches where vector similarity is high (>= 0.94)
 * but the underlying semantic intent diverges due to:
 * 1. Temporal anchors (e.g., "What is tax rate in India?" vs "What was tax rate in India in 2020?")
 * 2. Polarity / Negation (e.g., "How to enable CORS" vs "How to disable CORS")
 * 3. Domain entities (e.g., "Sort array in Python" vs "Sort array in Java")
 * 4. Geographic jurisdiction (e.g., "...in India" vs "...in USA")
 * 5. Numeric parameters (e.g., "top 5 algorithms" vs "top 10 algorithms")
 */
@Component
@Slf4j
public class ContextualMatchVerifier {

    public record VerificationResult(boolean matched, String reason) {
        public static VerificationResult accept() {
            return new VerificationResult(true, "SEMANTIC_AND_CONTEXT_EQUIVALENT");
        }

        public static VerificationResult reject(String reason) {
            return new VerificationResult(false, reason);
        }
    }

    private static final Pattern YEAR_PATTERN = Pattern.compile("\\b(19\\d{2}|20\\d{2})\\b");
    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\b\\d+\\b");

    private static final Pattern NEGATION_PATTERN = Pattern.compile("(?i)\\b(not|never|no|without|disable|disabled|prevent|block|stop|avoid|fail|deactivate)\\b");
    private static final Pattern AFFIRMATIVE_PATTERN = Pattern.compile("(?i)\\b(enable|enabled|allow|with|activate)\\b");

    private static final Pattern PAST_TENSE_PATTERN = Pattern.compile("(?i)\\b(was|were|history|historical|past|previous|formerly|earlier)\\b");
    private static final Pattern PRESENT_TENSE_PATTERN = Pattern.compile("(?i)\\b(is|are|current|currently|now|present|today|latest)\\b");

    private static final List<String> TECH_ENTITIES = List.of(
            "java", "python", "rust", "golang", "go", "c++", "cpp", "c#",
            "typescript", "javascript", "php", "ruby", "swift", "kotlin", "scala"
    );

    private static final List<String> REGION_ENTITIES = List.of(
            "india", "usa", "us", "uk", "germany", "japan", "france", "canada",
            "australia", "china", "singapore", "brazil"
    );

    public VerificationResult verify(String queryPrompt, String cachedPrompt) {
        if (queryPrompt == null || cachedPrompt == null) {
            return VerificationResult.reject("NULL_PROMPT");
        }

        // 1. Temporal Year Verification
        Set<String> queryYears = extractYears(queryPrompt);
        Set<String> cachedYears = extractYears(cachedPrompt);
        if (!queryYears.equals(cachedYears)) {
            log.info("Contextual match rejected [TEMPORAL_YEAR_DIVERGENCE]: query={}, cached={}", queryYears, cachedYears);
            return VerificationResult.reject("TEMPORAL_YEAR_DIVERGENCE: " + queryYears + " vs " + cachedYears);
        }

        // 2. Relative Temporal Tense Verification (e.g. "What is..." vs "What was... in the past")
        boolean queryHasPast = PAST_TENSE_PATTERN.matcher(queryPrompt).find();
        boolean cachedHasPast = PAST_TENSE_PATTERN.matcher(cachedPrompt).find();
        boolean queryHasPresent = PRESENT_TENSE_PATTERN.matcher(queryPrompt).find();
        boolean cachedHasPresent = PRESENT_TENSE_PATTERN.matcher(cachedPrompt).find();

        if ((queryHasPast && !cachedHasPast && cachedHasPresent) ||
            (!queryHasPast && queryHasPresent && cachedHasPast)) {
            log.info("Contextual match rejected [TEMPORAL_TENSE_DIVERGENCE]");
            return VerificationResult.reject("TEMPORAL_TENSE_DIVERGENCE");
        }

        // 3. Negation & Polarity Verification (e.g. enable vs disable)
        boolean queryNeg = NEGATION_PATTERN.matcher(queryPrompt).find();
        boolean cachedNeg = NEGATION_PATTERN.matcher(cachedPrompt).find();
        boolean queryAff = AFFIRMATIVE_PATTERN.matcher(queryPrompt).find();
        boolean cachedAff = AFFIRMATIVE_PATTERN.matcher(cachedPrompt).find();

        if ((queryNeg && !cachedNeg && cachedAff) ||
            (!queryNeg && queryAff && cachedNeg)) {
            log.info("Contextual match rejected [POLARITY_DIVERGENCE]");
            return VerificationResult.reject("POLARITY_DIVERGENCE");
        }

        // 4. Technology Entity Verification
        Set<String> queryTech = extractEntities(queryPrompt, TECH_ENTITIES);
        Set<String> cachedTech = extractEntities(cachedPrompt, TECH_ENTITIES);
        if (!queryTech.isEmpty() && !cachedTech.isEmpty() && !queryTech.equals(cachedTech)) {
            log.info("Contextual match rejected [TECH_STACK_DIVERGENCE]: query={}, cached={}", queryTech, cachedTech);
            return VerificationResult.reject("TECH_STACK_DIVERGENCE: " + queryTech + " vs " + cachedTech);
        }

        // 5. Geographic Jurisdiction Verification
        Set<String> queryRegions = extractEntities(queryPrompt, REGION_ENTITIES);
        Set<String> cachedRegions = extractEntities(cachedPrompt, REGION_ENTITIES);
        if (!queryRegions.isEmpty() && !cachedRegions.isEmpty() && !queryRegions.equals(cachedRegions)) {
            log.info("Contextual match rejected [JURISDICTION_DIVERGENCE]: query={}, cached={}", queryRegions, cachedRegions);
            return VerificationResult.reject("JURISDICTION_DIVERGENCE: " + queryRegions + " vs " + cachedRegions);
        }

        // 6. Numeric Parameter Verification (excluding years)
        Set<String> queryNums = extractNumbersExcludingYears(queryPrompt, queryYears);
        Set<String> cachedNums = extractNumbersExcludingYears(cachedPrompt, cachedYears);
        if (!queryNums.isEmpty() && !cachedNums.isEmpty() && !queryNums.equals(cachedNums)) {
            log.info("Contextual match rejected [NUMERIC_PARAM_DIVERGENCE]: query={}, cached={}", queryNums, cachedNums);
            return VerificationResult.reject("NUMERIC_PARAM_DIVERGENCE: " + queryNums + " vs " + cachedNums);
        }

        return VerificationResult.accept();
    }

    private Set<String> extractYears(String text) {
        Set<String> years = new HashSet<>();
        Matcher m = YEAR_PATTERN.matcher(text);
        while (m.find()) {
            years.add(m.group(1));
        }
        return years;
    }

    private Set<String> extractEntities(String text, List<String> catalog) {
        Set<String> found = new HashSet<>();
        String lower = text.toLowerCase();
        for (String entity : catalog) {
            Pattern p = Pattern.compile("\\b" + Pattern.quote(entity) + "\\b");
            if (p.matcher(lower).find()) {
                found.add(entity);
            }
        }
        return found;
    }

    private Set<String> extractNumbersExcludingYears(String text, Set<String> years) {
        Set<String> nums = new HashSet<>();
        Matcher m = NUMBER_PATTERN.matcher(text);
        while (m.find()) {
            String num = m.group(0);
            if (!years.contains(num)) {
                nums.add(num);
            }
        }
        return nums;
    }
}
