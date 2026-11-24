package com.orvix.guardrail.engine;

import java.util.HashMap;
import java.util.Map;

public final class EntropyCalculator {
    private EntropyCalculator() {}

    /**
     * Calculates Shannon entropy: H(X) = -sum(P(x) * log2(P(x)))
     */
    public static double calculateShannonEntropy(String input) {
        if (input == null || input.isEmpty()) {
            return 0.0;
        }
        Map<Character, Integer> freqMap = new HashMap<>();
        for (char c : input.toCharArray()) {
            freqMap.put(c, freqMap.getOrDefault(c, 0) + 1);
        }

        double entropy = 0.0;
        double len = input.length();
        for (int count : freqMap.values()) {
            double p = count / len;
            entropy -= p * (Math.log(p) / Math.log(2));
        }
        return entropy;
    }

    public static boolean isHighEntropySecret(String token) {
        if (token == null || token.length() < 24) {
            return false;
        }
        // Check if token contains only typical secret characters (base64, hex, alphanumeric)
        if (!token.matches("^[A-Za-z0-9_\\-\\+/=]+$")) {
            return false;
        }
        // Shannon entropy threshold for randomly generated cryptographic keys
        return calculateShannonEntropy(token) >= 3.8;
    }
}
