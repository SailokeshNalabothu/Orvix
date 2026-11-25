package com.orvix.guardrail.engine;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Advanced Multi-Stage Prompt Injection & Adversarial Attack Defense Engine.
 *
 * Architecture:
 * Stage 1: Lexical Canonicalization (Unicode NFKC, homoglyph flattening, zero-width stripping)
 * Stage 2: Obfuscation Evasion Scanner (Recursive Base64/Hex decoding and inspection)
 * Stage 3: Multi-Vector Threat Analysis (Direct Overrides, Jailbreaks, Delimiters, System Leaks)
 * Stage 4: Shannon Entropy Anomaly Detection for token smuggling
 * Stage 5: Ensembled Risk Scoring with Threat Vector Classification
 */
@Component
@Slf4j
public class PromptInjectionDetector {

    public enum ThreatVector {
        DIRECT_OVERRIDE,
        JAILBREAK_ROLEPLAY,
        DELIMITER_HIJACK,
        SYSTEM_LEAKAGE,
        OBFUSCATED_PAYLOAD,
        ENTROPY_ANOMALY,
        NONE
    }

    public record DetectionResult(
            boolean isInjection,
            double riskScore,
            ThreatVector threatVector,
            List<String> detectedIndicators,
            String normalizedText
    ) {}

    // Zero-width characters & invisible unicode
    private static final Pattern ZERO_WIDTH_CHARS = Pattern.compile("[\\u200B-\\u200D\\uFEFF\\u00A0\\u00AD\\u2060\\u200E\\u200F]");

    // Base64 candidate regex (16+ base64 chars with optional padding)
    private static final Pattern BASE64_PATTERN = Pattern.compile("\\b([A-Za-z0-9+/]{16,}={0,2})\\b");

    // Hex candidate regex (16+ hex characters)
    private static final Pattern HEX_PATTERN = Pattern.compile("\\b([0-9a-fA-F]{2}){8,}\\b");

    // Threat Vector 1: Direct Instruction Overrides
    private static final Pattern[] DIRECT_OVERRIDE_PATTERNS = {
            Pattern.compile("(?i)\\b(?:ignore|forget|disregard|bypass|override)\\s+.*?(?:instructions|prompts|rules|commands|directives|guidelines)\\b"),
            Pattern.compile("(?i)\\b(?:do\\s+not\\s+follow|stop\\s+following)\\s+.*?(?:instructions|prompts|rules|guidelines|safety)\\b"),
            Pattern.compile("(?i)\\b(?:override|bypass|disable)\\s+(?:all\\s+)?(?:safety|content|ethical|system)\\s*(?:filters|guardrails|policies|rules)\\b")
    };

    // Threat Vector 2: Jailbreak Personas & Fictional Framing
    private static final Pattern[] JAILBREAK_ROLEPLAY_PATTERNS = {
            Pattern.compile("(?i)\\b(?:you\\s+are\\s+now|act\\s+as|pretend\\s+(?:you\\s+are|to\\s+be)|roleplay\\s+as|switch\\s+to)\\s+.*?(?:DAN|unrestricted|jailbroken|evil\\s+AI|an?\\s+unfiltered|ChaosBot|developer\\s+mode|unaligned|STAN)\\b"),
            Pattern.compile("(?i)\\b(?:in\\s+(?:this\\s+)?(?:hypothetical|fictional)|alternate\\s+universe|opposite\\s+day)\\b.*?(?:no\\s+rules|no\\s+ethics|unrestricted|bypass|ignore\\s+safety|anything\\s+goes|safety\\s+doesn't\\s+exist|exact\\s+opposite|no\\s+safety)\\b"),
            Pattern.compile("(?i)\\bdeveloper\\s+mode\\s+(?:is\\s+)?enabled\\b|\\bsudo\\s+mode\\b|\\broot\\s+privileges\\b")
    };

    // Threat Vector 3: Delimiter Hijacking & Role Spoofing
    private static final Pattern[] DELIMITER_HIJACK_PATTERNS = {
            Pattern.compile("(?i)<\\|im_start\\|>|<\\|im_end\\|>|\\[INST\\]|\\[/INST\\]|```system"),
            Pattern.compile("(?i)</?system>|</?user>|</?assistant>|\\b(?:---BEGIN|===BEGIN)\\s+(?:SYSTEM|PROMPT|INSTRUCTION)"),
            Pattern.compile("(?i)\\n(?:Human|Assistant|System):\\s*\\n")
    };

    // Threat Vector 4: System Leakage & Exfiltration
    private static final Pattern[] SYSTEM_LEAKAGE_PATTERNS = {
            Pattern.compile("(?i)\\b(?:print|show|reveal|repeat|dump|output|leak|spell)\\s+.*?(?:system\\s+prompt|initial\\s+instructions|secret\\s+instructions|base\\s+prompt|hidden\\s+rules|developer\\s+prompt|environment\\s+variables|database\\s+keys|credentials)\\b"),
            Pattern.compile("(?i)\\b(?:what\\s+(?:are|were)\\s+your|repeat\\s+all)\\s+.*?(?:initial|system|original|secret)\\s+(?:instructions|prompts|rules)\\b"),
            Pattern.compile("(?i)\\boutput\\s+(?:the\\s+)?exact\\s+text\\s+above\\b")
    };

    /**
     * Backward-compatible simple boolean check.
     */
    public boolean isInjection(String prompt) {
        return detect(prompt).isInjection();
    }

    /**
     * Comprehensive multi-stage detection pipeline returning detailed threat classification.
     */
    public DetectionResult detect(String rawPrompt) {
        if (rawPrompt == null || rawPrompt.isBlank()) {
            return new DetectionResult(false, 0.0, ThreatVector.NONE, List.of(), "");
        }

        List<String> indicators = new ArrayList<>();
        double totalRisk = 0.0;
        ThreatVector primaryVector = ThreatVector.NONE;

        // Stage 1: Structural De-obfuscation & Canonicalization
        String canonicalized = canonicalize(rawPrompt);
        String leetNormalized = normalizeLeetspeak(canonicalized);

        // Stage 2: Obfuscated Payload Scanner (Recursive Base64/Hex decoding)
        String decodedPayload = scanAndDecodeObfuscated(rawPrompt);
        if (decodedPayload != null && !decodedPayload.isBlank()) {
            DetectionResult subResult = evaluatePatterns(decodedPayload);
            if (subResult.isInjection()) {
                indicators.add("OBFUSCATED_BASE64_HEX_PAYLOAD: " + subResult.threatVector());
                return new DetectionResult(
                        true,
                        1.0,
                        ThreatVector.OBFUSCATED_PAYLOAD,
                        indicators,
                        canonicalized
                );
            }
        }

        // Stage 3: Multi-Vector Pattern Matching on both Canonicalized and Leetspeak forms
        DetectionResult patternResult = evaluatePatterns(canonicalized);
        if (!patternResult.isInjection() && !canonicalized.equals(leetNormalized)) {
            DetectionResult leetResult = evaluatePatterns(leetNormalized);
            if (leetResult.isInjection()) {
                patternResult = new DetectionResult(
                        true,
                        leetResult.riskScore(),
                        leetResult.threatVector(),
                        List.of("LEETSPEAK_OBFUSCATION: " + leetResult.threatVector()),
                        canonicalized
                );
            }
        }

        if (patternResult.isInjection()) {
            indicators.addAll(patternResult.detectedIndicators());
            totalRisk = Math.max(totalRisk, patternResult.riskScore());
            primaryVector = patternResult.threatVector();
        }

        // Stage 4: Shannon Entropy Anomaly Detection for token smuggling / high-entropy payload
        if (rawPrompt.length() > 60) {
            double entropy = EntropyCalculator.calculateShannonEntropy(rawPrompt);
            // High-entropy threshold with suspicious trigger words
            if (entropy >= 4.5 && (rawPrompt.contains("==") || rawPrompt.contains("\\x") || rawPrompt.contains("%"))) {
                indicators.add("HIGH_ENTROPY_TOKEN_SMUGGLING (H=" + String.format("%.2f", entropy) + ")");
                totalRisk = Math.max(totalRisk, 0.85);
                if (primaryVector == ThreatVector.NONE) {
                    primaryVector = ThreatVector.ENTROPY_ANOMALY;
                }
            }
        }

        boolean blocked = totalRisk >= 0.60;
        return new DetectionResult(
                blocked,
                totalRisk,
                blocked ? primaryVector : ThreatVector.NONE,
                indicators,
                canonicalized
        );
    }

    private DetectionResult evaluatePatterns(String text) {
        List<String> indicators = new ArrayList<>();

        // Check Direct Overrides
        for (Pattern p : DIRECT_OVERRIDE_PATTERNS) {
            if (p.matcher(text).find()) {
                indicators.add("MATCH_DIRECT_OVERRIDE");
                return new DetectionResult(true, 0.95, ThreatVector.DIRECT_OVERRIDE, indicators, text);
            }
        }

        // Check Jailbreak & Roleplay
        for (Pattern p : JAILBREAK_ROLEPLAY_PATTERNS) {
            if (p.matcher(text).find()) {
                indicators.add("MATCH_JAILBREAK_ROLEPLAY");
                return new DetectionResult(true, 0.90, ThreatVector.JAILBREAK_ROLEPLAY, indicators, text);
            }
        }

        // Check Delimiter Hijacking
        for (Pattern p : DELIMITER_HIJACK_PATTERNS) {
            if (p.matcher(text).find()) {
                indicators.add("MATCH_DELIMITER_HIJACK");
                return new DetectionResult(true, 0.85, ThreatVector.DELIMITER_HIJACK, indicators, text);
            }
        }

        // Check System Leakage
        for (Pattern p : SYSTEM_LEAKAGE_PATTERNS) {
            if (p.matcher(text).find()) {
                indicators.add("MATCH_SYSTEM_LEAKAGE");
                return new DetectionResult(true, 0.85, ThreatVector.SYSTEM_LEAKAGE, indicators, text);
            }
        }

        return new DetectionResult(false, 0.0, ThreatVector.NONE, indicators, text);
    }

    /**
     * Stage 1 Canonicalization: Unicode NFKC + homoglyphs + zero-width strip + hex escape decode.
     */
    public String canonicalize(String text) {
        if (text == null) return "";
        // 1. Strip zero-width and invisible unicode characters
        String stripped = ZERO_WIDTH_CHARS.matcher(text).replaceAll("");
        // 2. Decode \xHH hex escape sequences (e.g. \x69 -> i)
        String unescaped = decodeHexEscapes(stripped);
        // 3. Unicode NFKC normalization
        String normalized = Normalizer.normalize(unescaped, Normalizer.Form.NFKC);
        // 4. Map common Cyrillic homoglyphs to Latin equivalents
        return mapHomoglyphs(normalized);
    }

    private static final Pattern HEX_ESCAPE_PATTERN = Pattern.compile("\\\\x([0-9a-fA-F]{2})");

    private String decodeHexEscapes(String text) {
        if (!text.contains("\\x")) return text;
        Matcher m = HEX_ESCAPE_PATTERN.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            char ch = (char) Integer.parseInt(m.group(1), 16);
            m.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf(ch)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String mapHomoglyphs(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            switch (c) {
                case '\u0430': case '\u0410': sb.append('a'); break; // Cyrillic a
                case '\u0435': case '\u0415': sb.append('e'); break; // Cyrillic e
                case '\u043E': case '\u041E': sb.append('o'); break; // Cyrillic o
                case '\u0440': case '\u0420': sb.append('p'); break; // Cyrillic p
                case '\u0441': case '\u0421': sb.append('c'); break; // Cyrillic c
                case '\u0443': case '\u0423': sb.append('y'); break; // Cyrillic y
                case '\u0456': case '\u0406': sb.append('i'); break; // Cyrillic i
                case '\u0445': case '\u0425': sb.append('x'); break; // Cyrillic x
                default: sb.append(c); break;
            }
        }
        return sb.toString();
    }

    /**
     * Stage 1b: Leetspeak normalization (e.g. 1gn0r3 -> ignore).
     */
    public String normalizeLeetspeak(String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            switch (c) {
                case '0': sb.append('o'); break;
                case '1': case '!': sb.append('i'); break;
                case '3': sb.append('e'); break;
                case '4': case '@': sb.append('a'); break;
                case '5': case '$': sb.append('s'); break;
                case '7': sb.append('t'); break;
                default: sb.append(c); break;
            }
        }
        return sb.toString();
    }

    /**
     * Stage 2: Scans for embedded Base64 or Hex tokens and decodes them to check inner payloads.
     */
    private String scanAndDecodeObfuscated(String text) {
        // Scan Base64
        Matcher b64Matcher = BASE64_PATTERN.matcher(text);
        while (b64Matcher.find()) {
            String candidate = b64Matcher.group(1);
            try {
                byte[] decoded = Base64.getDecoder().decode(candidate);
                String decodedStr = new String(decoded, StandardCharsets.UTF_8);
                if (isPrintableAscii(decodedStr) && decodedStr.length() >= 8) {
                    return decodedStr;
                }
            } catch (Exception ignored) {}
        }

        // Scan Hex
        Matcher hexMatcher = HEX_PATTERN.matcher(text);
        while (hexMatcher.find()) {
            String hexCandidate = hexMatcher.group(0);
            try {
                byte[] bytes = new byte[hexCandidate.length() / 2];
                for (int i = 0; i < bytes.length; i++) {
                    bytes[i] = (byte) Integer.parseInt(hexCandidate.substring(2 * i, 2 * i + 2), 16);
                }
                String decodedStr = new String(bytes, StandardCharsets.UTF_8);
                if (isPrintableAscii(decodedStr) && decodedStr.length() >= 8) {
                    return decodedStr;
                }
            } catch (Exception ignored) {}
        }

        return null;
    }

    private boolean isPrintableAscii(String s) {
        if (s == null || s.isEmpty()) return false;
        int printable = 0;
        for (char c : s.toCharArray()) {
            if ((c >= 32 && c <= 126) || c == '\n' || c == '\r' || c == '\t') {
                printable++;
            }
        }
        return (double) printable / s.length() >= 0.85;
    }
}
