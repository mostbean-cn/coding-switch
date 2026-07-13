package com.github.mostbean.codingswitch.service;

import java.util.Locale;

/** Codex 上下文窗口显示值与 token 数之间的转换工具。 */
public final class CodexContextWindowSupport {

    private CodexContextWindowSupport() {
    }

    public static long parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Context window is required");
        }

        String normalized = value.trim().replace("_", "").toUpperCase(Locale.ROOT);
        long multiplier = 1L;
        if (normalized.endsWith("K")) {
            multiplier = 1_000L;
            normalized = normalized.substring(0, normalized.length() - 1).trim();
        } else if (normalized.endsWith("M")) {
            multiplier = 1_000_000L;
            normalized = normalized.substring(0, normalized.length() - 1).trim();
        }

        try {
            long parsed = Math.multiplyExact(Long.parseLong(normalized), multiplier);
            if (parsed <= 0) {
                throw new IllegalArgumentException("Context window must be positive");
            }
            return parsed;
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Invalid context window: " + value, exception);
        }
    }

    public static String format(long contextWindow) {
        return Long.toString(contextWindow);
    }
}
