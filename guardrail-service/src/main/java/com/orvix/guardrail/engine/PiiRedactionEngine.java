package com.orvix.guardrail.engine;

import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class PiiRedactionEngine {

    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b"
    );

    private static final Pattern CREDIT_CARD_CANDIDATE_PATTERN = Pattern.compile(
            "\\b(?:\\d[ -]*?){13,19}\\b"
    );

    private static final Pattern KNOWN_API_KEY_PATTERN = Pattern.compile(
            "\\b(?:sk-[a-zA-Z0-9_-]{20,}|ghp_[a-zA-Z0-9]{36}|AKIA[0-9A-Z]{16}|AIza[0-9A-Za-z-_]{35}|Bearer\\s+[a-zA-Z0-9._~+/-]{20,})\\b"
    );

    private static final Pattern US_SSN_PATTERN = Pattern.compile(
            "\\b(?!000|666|9\\d{2})\\d{3}-(?!00)\\d{2}-(?!0000)\\d{4}\\b"
    );

    private static final Pattern AADHAAR_PATTERN = Pattern.compile(
            "\\b[2-9]\\d{3}\\s\\d{4}\\s\\d{4}\\b"
    );

    public record RedactionResult(
            String sanitizedText,
            int redactionsCount,
            List<String> violations
    ) {}

    public RedactionResult redact(String text) {
        if (text == null || text.isBlank()) {
            return new RedactionResult(text, 0, List.of());
        }

        String current = text;
        List<String> violations = new ArrayList<>();
        int count = 0;

        // 1. Redact Emails
        Matcher emailMatcher = EMAIL_PATTERN.matcher(current);
        if (emailMatcher.find()) {
            violations.add("PII_EMAIL");
            StringBuffer sb = new StringBuffer();
            emailMatcher.reset();
            while (emailMatcher.find()) {
                emailMatcher.appendReplacement(sb, "[REDACTED_EMAIL]");
                count++;
            }
            emailMatcher.appendTail(sb);
            current = sb.toString();
        }

        // 2. Redact US SSN
        Matcher ssnMatcher = US_SSN_PATTERN.matcher(current);
        if (ssnMatcher.find()) {
            violations.add("PII_SSN");
            StringBuffer sb = new StringBuffer();
            ssnMatcher.reset();
            while (ssnMatcher.find()) {
                ssnMatcher.appendReplacement(sb, "[REDACTED_SSN]");
                count++;
            }
            ssnMatcher.appendTail(sb);
            current = sb.toString();
        }

        // 3. Redact Indian Aadhaar
        Matcher aadhaarMatcher = AADHAAR_PATTERN.matcher(current);
        if (aadhaarMatcher.find()) {
            violations.add("PII_AADHAAR");
            StringBuffer sb = new StringBuffer();
            aadhaarMatcher.reset();
            while (aadhaarMatcher.find()) {
                aadhaarMatcher.appendReplacement(sb, "[REDACTED_AADHAAR]");
                count++;
            }
            aadhaarMatcher.appendTail(sb);
            current = sb.toString();
        }

        // 4. Redact Known API Keys
        Matcher apiKeyMatcher = KNOWN_API_KEY_PATTERN.matcher(current);
        if (apiKeyMatcher.find()) {
            violations.add("SECRET_API_KEY");
            StringBuffer sb = new StringBuffer();
            apiKeyMatcher.reset();
            while (apiKeyMatcher.find()) {
                apiKeyMatcher.appendReplacement(sb, "[REDACTED_API_KEY]");
                count++;
            }
            apiKeyMatcher.appendTail(sb);
            current = sb.toString();
        }

        // 5. Redact Credit Cards with Luhn Verification
        Matcher ccMatcher = CREDIT_CARD_CANDIDATE_PATTERN.matcher(current);
        StringBuffer ccSb = new StringBuffer();
        boolean foundValidCC = false;
        while (ccMatcher.find()) {
            String candidate = ccMatcher.group();
            if (LuhnValidator.isValid(candidate)) {
                foundValidCC = true;
                ccMatcher.appendReplacement(ccSb, "[REDACTED_CREDIT_CARD]");
                count++;
            } else {
                ccMatcher.appendReplacement(ccSb, Matcher.quoteReplacement(candidate));
            }
        }
        ccMatcher.appendTail(ccSb);
        current = ccSb.toString();
        if (foundValidCC) {
            violations.add("PII_CREDIT_CARD");
        }

        // 6. High-Entropy Secret Detection for Words
        String[] words = current.split("\\s+");
        boolean foundEntropySecret = false;
        for (String word : words) {
            String cleanWord = word.replaceAll("[^A-Za-z0-9_\\-\\+/=]", "");
            if (EntropyCalculator.isHighEntropySecret(cleanWord) && !cleanWord.contains("REDACTED")) {
                current = current.replace(cleanWord, "[REDACTED_SECRET]");
                count++;
                foundEntropySecret = true;
            }
        }
        if (foundEntropySecret && !violations.contains("SECRET_API_KEY")) {
            violations.add("SECRET_HIGH_ENTROPY");
        }

        return new RedactionResult(current, count, violations);
    }
}
