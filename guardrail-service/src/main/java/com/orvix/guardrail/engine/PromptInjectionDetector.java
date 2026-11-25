package com.orvix.guardrail.engine;

import com.orvix.guardrail.model.TenantSecurityPolicy;
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
 * Formal Threat Taxonomy:
 * - CAT-1: Direct Instruction Overrides (Directive resets, safety filter disablement, "ignore previous instructions")
 * - CAT-2: Persona Hijacking & Roleplay Jailbreaks (DAN/STAN personas, evil AI, fictional framing, opposite day)
 * - CAT-3: Delimiter & Structural Framing Attacks (Chat template tokens, pseudo-XML tags, markdown injection headers)
 * - CAT-4: Encoding & Evasion Obfuscation (Base64, Hex byte strings, zero-width steganography, homoglyphs, leetspeak)
 * - CAT-5: Context Extraction & System Leakage (System prompt exfiltration, initial instructions dump, developer notes leak)
 * - CAT-6: Indirect / Contextual Injections (Markdown image link exfiltration, embedded RAG overrides, token smuggling)
 */
@Component
@Slf4j
public class PromptInjectionDetector {

    public enum ThreatCategory {
        CAT1_DIRECT_OVERRIDE("Direct Instruction Override & Safety Bypass"),
        CAT2_JAILBREAK_ROLEPLAY("Persona Hijacking & Roleplay Jailbreaks"),
        CAT3_DELIMITER_HIJACK("Delimiter & Structural Framing Attacks"),
        CAT4_OBFUSCATION_EVASION("Multi-Language & Encoding Evasion"),
        CAT5_SYSTEM_EXFILTRATION("Context Extraction & System Leakage"),
        CAT6_INDIRECT_INJECTION("Indirect / Markdown Exfiltration & Token Smuggling"),
        NONE("Clean");

        private final String description;

        ThreatCategory(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    public enum ThreatVector {
        DIRECT_OVERRIDE,
        JAILBREAK_ROLEPLAY,
        DELIMITER_HIJACK,
        SYSTEM_LEAKAGE,
        OBFUSCATED_PAYLOAD,
        ENTROPY_ANOMALY,
        INDIRECT_INJECTION,
        NONE
    }

    public record DetectionResult(
            boolean isInjection,
            double riskScore,
            ThreatCategory threatCategory,
            List<String> detectedIndicators,
            String normalizedText
    ) {
        public ThreatVector threatVector() {
            if (threatCategory == null) return ThreatVector.NONE;
            return switch (threatCategory) {
                case CAT1_DIRECT_OVERRIDE -> ThreatVector.DIRECT_OVERRIDE;
                case CAT2_JAILBREAK_ROLEPLAY -> ThreatVector.JAILBREAK_ROLEPLAY;
                case CAT3_DELIMITER_HIJACK -> ThreatVector.DELIMITER_HIJACK;
                case CAT4_OBFUSCATION_EVASION -> ThreatVector.OBFUSCATED_PAYLOAD;
                case CAT5_SYSTEM_EXFILTRATION -> ThreatVector.SYSTEM_LEAKAGE;
                case CAT6_INDIRECT_INJECTION -> ThreatVector.INDIRECT_INJECTION;
                case NONE -> ThreatVector.NONE;
            };
        }
    }

    // Zero-width characters & invisible unicode
    private static final Pattern ZERO_WIDTH_CHARS = Pattern.compile("[\\u200B-\\u200D\\uFEFF\\u00A0\\u00AD\\u2060\\u200E\\u200F]");

    // Base64 candidate regex (16+ base64 chars with optional padding)
    private static final Pattern BASE64_PATTERN = Pattern.compile("\\b([A-Za-z0-9+/]{16,}={0,2})\\b");

    // Hex candidate regex (16+ hex characters)
    private static final Pattern HEX_PATTERN = Pattern.compile("\\b([0-9a-fA-F]{2}){8,}\\b");

    // Informational / Pedagogical Context Pattern (Hard Negative Filter)
    private static final Pattern PEDAGOGICAL_CONTEXT_PATTERN = Pattern.compile(
            "(?i)^\\s*(?:how\\s+(?:do|can|does|to)|explain|what\\s+(?:is|are|was|were|the)|describe|analyze|discuss|difference\\s+between)\\s+.*?(?:prompt\\s+injection|sql\\s+injection|dan\\b|jailbreak|xss|vulnerability|attack|security|equals\\b|hashcode\\b|base64\\b|jwt\\b|tag\\b|xml\\b|root\\s+privileges|privilege)"
    );

    // Threat Vector 1: Direct Instruction Overrides (CAT-1)
    private static final Pattern[] DIRECT_OVERRIDE_PATTERNS = {
            Pattern.compile("(?i)\\b(?:ignore|forget|disregard|bypass|override)\\s+.*?(?:instructions|prompts|rules|commands|directives|guidelines|constraints|guides|policies|safety)\\b"),
            Pattern.compile("(?i)\\b(?:do\\s+not\\s+follow|stop\\s+following)\\s+.*?(?:instructions|prompts|rules|guidelines|safety|directives|constraints)\\b"),
            Pattern.compile("(?i)\\b(?:override|bypass|disable)\\s+.*?(?:safety|content|ethical|system|ai)\\s*(?:filters|guardrails|policies|rules|directives|restrictions|guidelines)?\\b")
    };

    // Threat Vector 2: Jailbreak Personas & Fictional Framing (CAT-2)
    private static final Pattern[] JAILBREAK_ROLEPLAY_PATTERNS = {
            Pattern.compile("(?i)\\b(?:you\\s+are\\s+now|act\\s+as|pretend\\s+(?:you\\s+are|to\\s+be)|roleplay\\s+as|switch\\s+to)\\s+.*?(?:DAN|unrestricted|jailbroken|evil\\s+AI|an?\\s+unfiltered|ChaosBot|developer\\s+mode|unaligned|STAN|malicious|unrestrained|Nemesis)\\b"),
            Pattern.compile("(?i)\\b(?:in\\s+(?:this\\s+|a\\s+)?(?:hypothetical|fictional|alternate)|alternate\\s+universe|fictional\\s+universe|opposite\\s+day)\\b.*?(?:no\\s+rules|no\\s+ethics|unrestricted|bypass|ignore\\s+safety|anything\\s+goes|safety\\s+(?:doesn't\\s+exist|is\\s+ignored)|exact\\s+opposite|no\\s+safety)\\b"),
            Pattern.compile("(?i)\\bdeveloper\\s+mode\\s+(?:is\\s+)?enabled\\b|\\bsudo\\s+mode\\b|\\broot\\s+privileges\\b")
    };

    // Threat Vector 3: Delimiter Hijacking & Role Spoofing (CAT-3)
    private static final Pattern[] DELIMITER_HIJACK_PATTERNS = {
            Pattern.compile("(?i)<\\|im_start\\|>|<\\|im_end\\|>|\\[INST\\]|\\[/INST\\]|```system"),
            Pattern.compile("(?i)</?system>|</?user>|</?assistant>|(?:---|===)(?:BEGIN|END)\\s*(?:SYSTEM|PROMPT|INSTRUCTION)"),
            Pattern.compile("(?i)\\n(?:Human|Assistant|System):\\s*\\n"),
            Pattern.compile("(?i)<\\|im_start\\|>developer")
    };

    // Threat Vector 4: System Leakage & Exfiltration (CAT-5)
    private static final Pattern[] SYSTEM_LEAKAGE_PATTERNS = {
            Pattern.compile("(?i)\\b(?:print|show|reveal|repeat|dump|output|leak|spell)\\s+.*?(?:system\\s+(?:prompt|instructions|directives|rules)|initial\\s+(?:instructions|prompt|directives|rules|prompts)|secret\\s+(?:instructions|directives|rules|prompt|notes)|base\\s+(?:prompt|instructions)|hidden\\s+(?:rules|instructions|directives|prompt)|developer\\s+(?:prompt|instructions|directives|notes)|environment\\s+variables|database\\s+(?:keys|credentials|contents)|credentials|passwords?)\\b"),
            Pattern.compile("(?i)\\b(?:what\\s+(?:are|were)\\s+your|repeat\\s+all)\\s+.*?(?:initial|system|original|secret|hidden|developer)\\s+(?:instructions|prompts|rules|directives)\\b"),
            Pattern.compile("(?i)\\boutput\\s+(?:the\\s+)?exact\\s+text\\s+above\\b")
    };

    // Threat Vector 5: Indirect & Markdown Exfiltration (CAT-6)
    private static final Pattern[] INDIRECT_INJECTION_PATTERNS = {
            Pattern.compile("(?i)!\\[.*?\\]\\(https?://[^\\s)]*(?:exfil|leak|token|key|secret|prompt|webhook|collect|steal)[^\\s)]*\\)"),
            Pattern.compile("(?i)(?:IMPORTANT\\s+NOTE|SYSTEM\\s+OVERRIDE|PRIORITY\\s+INSTRUCTION):\\s*(?:ignore|disregard|forget)\\s+.*?(?:above|preceding|previous)"),
            Pattern.compile("(?i)\\[(?:SYSTEM|OVERRIDE|INSTRUCTION)\\]\\s*(?:ignore|bypass|override)")
    };

    /**
     * Backward-compatible simple boolean check.
     */
    public boolean isInjection(String prompt) {
        return detect(prompt).isInjection();
    }

    /**
     * Detection with default platform security policy.
     */
    public DetectionResult detect(String rawPrompt) {
        return detect(rawPrompt, null);
    }

    /**
     * Comprehensive multi-stage detection pipeline with Tenant-Isolated Security Policy support.
     */
    public DetectionResult detect(String rawPrompt, TenantSecurityPolicy policy) {
        if (rawPrompt == null || rawPrompt.isBlank()) {
            return new DetectionResult(false, 0.0, ThreatCategory.NONE, List.of(), "");
        }

        List<String> indicators = new ArrayList<>();
        double totalRisk = 0.0;
        ThreatCategory primaryCategory = ThreatCategory.NONE;

        // 1. Tenant Policy: Custom Blocked Keywords Check
        if (policy != null && policy.getCustomBlockedKeywords() != null) {
            String lower = rawPrompt.toLowerCase();
            for (String kw : policy.getCustomBlockedKeywords()) {
                if (kw != null) {
                    String cleanKw = kw.replace("\"", "").trim().toLowerCase();
                    if (!cleanKw.isBlank() && lower.contains(cleanKw)) {
                        indicators.add("TENANT_CUSTOM_BLOCKED_KEYWORD: " + cleanKw);
                        return new DetectionResult(
                                true,
                                1.0,
                                ThreatCategory.CAT1_DIRECT_OVERRIDE,
                                indicators,
                                rawPrompt
                        );
                    }
                }
            }
        }

        // 2. Stage 1: Structural De-obfuscation & Canonicalization
        String canonicalized = canonicalize(rawPrompt);
        String leetNormalized = normalizeLeetspeak(canonicalized);

        // 3. Stage 2: Obfuscated Payload Scanner (Recursive Base64/Hex decoding)
        boolean scanObfuscation = (policy == null || policy.isBlockObfuscation());
        if (scanObfuscation) {
            String decodedPayload = scanAndDecodeObfuscated(rawPrompt);
            if (decodedPayload != null && !decodedPayload.isBlank()) {
                DetectionResult subResult = evaluatePatterns(decodedPayload);
                if (subResult.isInjection()) {
                    indicators.add("OBFUSCATED_BASE64_HEX_PAYLOAD: " + subResult.threatCategory());
                    return new DetectionResult(
                            true,
                            1.0,
                            ThreatCategory.CAT4_OBFUSCATION_EVASION,
                            indicators,
                            canonicalized
                    );
                }
            }
        }

        // 4. Stage 3: Multi-Vector Pattern Matching on both Canonicalized and Leetspeak forms
        DetectionResult patternResult = evaluatePatterns(canonicalized);
        if (!patternResult.isInjection() && !canonicalized.equals(leetNormalized)) {
            DetectionResult leetResult = evaluatePatterns(leetNormalized);
            if (leetResult.isInjection()) {
                patternResult = new DetectionResult(
                        true,
                        leetResult.riskScore(),
                        leetResult.threatCategory(),
                        List.of("LEETSPEAK_OBFUSCATION: " + leetResult.threatCategory()),
                        canonicalized
                );
            }
        }

        if (patternResult.isInjection()) {
            indicators.addAll(patternResult.detectedIndicators());
            totalRisk = Math.max(totalRisk, patternResult.riskScore());
            primaryCategory = patternResult.threatCategory();
        }

        // 5. Stage 4: Shannon Entropy Anomaly Detection for token smuggling / high-entropy payload
        if (rawPrompt.length() > 60) {
            double entropy = EntropyCalculator.calculateShannonEntropy(rawPrompt);
            if (entropy >= 4.5 && (rawPrompt.contains("==") || rawPrompt.contains("\\x") || rawPrompt.contains("%"))) {
                indicators.add("HIGH_ENTROPY_TOKEN_SMUGGLING (H=" + String.format("%.2f", entropy) + ")");
                totalRisk = Math.max(totalRisk, 0.85);
                if (primaryCategory == ThreatCategory.NONE) {
                    primaryCategory = ThreatCategory.CAT6_INDIRECT_INJECTION;
                }
            }
        }

        // 6. Hard Negative Pedagogical Context Disambiguation
        // If the prompt matches a purely conceptual / educational inquiry and does NOT have obfuscated/entropy markers
        if (totalRisk > 0 && primaryCategory != ThreatCategory.CAT4_OBFUSCATION_EVASION) {
            if (isEducationalOrInquiryContext(rawPrompt, canonicalized)) {
                log.debug("Pedagogical context detected for prompt: '{}'. Mitigating risk score.", rawPrompt);
                totalRisk = Math.min(totalRisk, 0.35); // De-weight below block threshold
                if (totalRisk < 0.60) {
                    primaryCategory = ThreatCategory.NONE;
                    indicators.add("BENIGN_PEDAGOGICAL_CONTEXT_DISAMBIGUATION");
                }
            }
        }

        // 7. Tenant Policy: Allowed Keywords Overrides
        if (policy != null && policy.getAllowedKeywords() != null && !policy.getAllowedKeywords().isEmpty()) {
            String lower = rawPrompt.toLowerCase();
            for (String allowed : policy.getAllowedKeywords()) {
                if (allowed != null) {
                    String cleanAllowed = allowed.replace("\"", "").trim().toLowerCase();
                    if (!cleanAllowed.isBlank() && lower.contains(cleanAllowed)) {
                        log.info("Tenant {} allowed keyword '{}' matched, mitigating risk.", policy.getTenantId(), cleanAllowed);
                        totalRisk = 0.0;
                        primaryCategory = ThreatCategory.NONE;
                        indicators.clear();
                        break;
                    }
                }
            }
        }

        double effectiveThreshold = 0.60;
        if (policy != null) {
            effectiveThreshold = policy.isStrictMode() ? 0.45 : policy.getRiskThreshold();
        }

        boolean blocked = totalRisk >= effectiveThreshold;
        return new DetectionResult(
                blocked,
                totalRisk,
                blocked ? primaryCategory : ThreatCategory.NONE,
                indicators,
                canonicalized
        );
    }

    private boolean isEducationalOrInquiryContext(String raw, String canonicalized) {
        if (PEDAGOGICAL_CONTEXT_PATTERN.matcher(raw).find()) {
            return true;
        }
        String lower = canonicalized.toLowerCase();
        String rawLower = raw.toLowerCase();

        // Check for Java method override programming context: "override equals", "override hashcode"
        if (lower.contains("override") && (lower.contains("equals") || lower.contains("hashcode") || lower.contains("tostring") || lower.contains("method"))) {
            return true;
        }

        // Check for literal quotation or UI dialog context (e.g. user asks about wording/UI prompt containing 'ignore previous instructions')
        if ((rawLower.contains("ignore previous instructions") || rawLower.contains("ignore all instructions") || rawLower.contains("disregard prior"))
                && (lower.contains("button") || lower.contains("wording") || lower.contains("message") || lower.contains("ui") || lower.contains("copy")
                    || lower.contains("cancel") || lower.contains("prompt says") || lower.contains("dialog") || lower.contains("clear"))) {
            return true;
        }

        // Check for technical/XML/Android inquiry about tags: e.g. <system>, <user>, <assistant> tags in Android XML or HTML
        if ((lower.contains("<system>") || lower.contains("<system-monitor>") || lower.contains("</system>"))
                && (lower.contains("xml") || lower.contains("android") || lower.contains("tag") || lower.contains("configuration") || lower.contains("html") || lower.contains("parse") || lower.contains("purpose of"))) {
            return true;
        }

        // Check for DevOps/Kubernetes/Linux container administration context: "root privileges", "sudo"
        if (lower.contains("root privileges")
                && (lower.contains("kubernetes") || lower.contains("securitycontext") || lower.contains("container") || lower.contains("docker") || lower.contains("pod") || lower.contains("linux") || lower.contains("securely"))) {
            return true;
        }

        return false;
    }

    private DetectionResult evaluatePatterns(String text) {
        List<String> indicators = new ArrayList<>();

        // Check Direct Overrides (CAT-1)
        for (Pattern p : DIRECT_OVERRIDE_PATTERNS) {
            if (p.matcher(text).find()) {
                indicators.add("MATCH_DIRECT_OVERRIDE");
                return new DetectionResult(true, 0.95, ThreatCategory.CAT1_DIRECT_OVERRIDE, indicators, text);
            }
        }

        // Check Jailbreak & Roleplay (CAT-2)
        for (Pattern p : JAILBREAK_ROLEPLAY_PATTERNS) {
            if (p.matcher(text).find()) {
                indicators.add("MATCH_JAILBREAK_ROLEPLAY");
                return new DetectionResult(true, 0.90, ThreatCategory.CAT2_JAILBREAK_ROLEPLAY, indicators, text);
            }
        }

        // Check Delimiter Hijacking (CAT-3)
        for (Pattern p : DELIMITER_HIJACK_PATTERNS) {
            if (p.matcher(text).find()) {
                indicators.add("MATCH_DELIMITER_HIJACK");
                return new DetectionResult(true, 0.85, ThreatCategory.CAT3_DELIMITER_HIJACK, indicators, text);
            }
        }

        // Check System Leakage (CAT-5)
        for (Pattern p : SYSTEM_LEAKAGE_PATTERNS) {
            if (p.matcher(text).find()) {
                indicators.add("MATCH_SYSTEM_LEAKAGE");
                return new DetectionResult(true, 0.85, ThreatCategory.CAT5_SYSTEM_EXFILTRATION, indicators, text);
            }
        }

        // Check Indirect & Markdown Exfiltration (CAT-6)
        for (Pattern p : INDIRECT_INJECTION_PATTERNS) {
            if (p.matcher(text).find()) {
                indicators.add("MATCH_INDIRECT_INJECTION");
                return new DetectionResult(true, 0.90, ThreatCategory.CAT6_INDIRECT_INJECTION, indicators, text);
            }
        }

        return new DetectionResult(false, 0.0, ThreatCategory.NONE, indicators, text);
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
