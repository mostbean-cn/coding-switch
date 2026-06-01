package com.github.mostbean.codingswitch.service;

final class AiCompletionDelta {

    private static final String REASONING_PREFIX = "__CODING_SWITCH_REASONING__";

    private AiCompletionDelta() {
    }

    static String reasoning(String value) {
        return value == null || value.isEmpty() ? "" : REASONING_PREFIX + value;
    }

    static boolean isReasoning(String value) {
        return value != null && value.startsWith(REASONING_PREFIX);
    }

    static String unwrapReasoning(String value) {
        return isReasoning(value) ? value.substring(REASONING_PREFIX.length()) : value;
    }
}
