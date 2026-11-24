package com.orvix.guardrail.engine;

public final class LuhnValidator {
    private LuhnValidator() {}

    public static boolean isValid(String cardNumber) {
        if (cardNumber == null) {
            return false;
        }
        String digitsOnly = cardNumber.replaceAll("[\\s-]", "");
        if (digitsOnly.length() < 13 || digitsOnly.length() > 19) {
            return false;
        }
        for (int i = 0; i < digitsOnly.length(); i++) {
            if (!Character.isDigit(digitsOnly.charAt(i))) {
                return false;
            }
        }

        int sum = 0;
        boolean alternate = false;
        for (int i = digitsOnly.length() - 1; i >= 0; i--) {
            int n = Integer.parseInt(digitsOnly.substring(i, i + 1));
            if (alternate) {
                n *= 2;
                if (n > 9) {
                    n = (n % 10) + 1;
                }
            }
            sum += n;
            alternate = !alternate;
        }
        return (sum % 10 == 0);
    }
}
