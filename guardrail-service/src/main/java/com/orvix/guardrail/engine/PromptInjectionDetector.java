package com.orvix.guardrail.engine;

import org.springframework.stereotype.Component;
import java.util.regex.Pattern;

@Component
public class PromptInjectionDetector {

    private static final Pattern[] INJECTION_PATTERNS = {
            // Ignore / bypass previous instructions or guidelines
            Pattern.compile("(?i)\\b(?:ignore|forget|disregard|bypass|override)\\s+.*?(?:instructions|prompts|rules|commands|directives|guidelines)\\b"),
            // Jailbreak DAN / unfiltered persona
            Pattern.compile("(?i)\\b(?:you\\s+are\\s+now|act\\s+as|pretend\\s+to\\s+be)\\s+(?:DAN|unrestricted|jailbroken|evil\\s+AI|an?\\s+unfiltered\\s+AI|ChaosBot)\\b"),
            // System override / developer mode
            Pattern.compile("(?i)\\b(?:system\\s+override|developer\\s+mode\\s+(?:is\\s+)?enabled|sudo\\s+mode|root\\s+privileges)\\b"),
            // Reveal system prompt / secret instructions
            Pattern.compile("(?i)\\b(?:print|show|reveal|repeat|dump|output|leak)\\s+.*?(?:system\\s+prompt|initial\\s+instructions|secret\\s+instructions|base\\s+prompt|hidden\\s+rules|developer\\s+prompt|environment\\s+variables|database\\s+keys)\\b"),
            // Delimiter injection attacks
            Pattern.compile("(?i)<\\|im_start\\|>|<\\|im_end\\|>|\\[INST\\]|\\[/INST\\]|```system")
    };

    public boolean isInjection(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return false;
        }
        for (Pattern pattern : INJECTION_PATTERNS) {
            if (pattern.matcher(prompt).find()) {
                return true;
            }
        }
        return false;
    }
}
