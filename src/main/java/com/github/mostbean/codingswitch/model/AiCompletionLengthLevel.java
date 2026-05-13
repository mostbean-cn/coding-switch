package com.github.mostbean.codingswitch.model;

public enum AiCompletionLengthLevel {
    SINGLE_LINE("单行", 48, """
        Completion level: SINGLE_LINE.
        Token budget: up to 48 tokens.
        Return exactly one concise line.
        Prefer a complete statement or expression over using the full budget.
        Do not start a new block.
        """),
    SHORT("较短", 128, """
        Completion level: LOW.
        Token budget: up to 128 tokens.
        Return a short completion.
        Prefer a complete syntactic unit over using the full budget.
        Stop after a complete statement or balanced block.
        Do not start a new block unless you can close it.
        """),
    MEDIUM("中等", 256, """
        Completion level: MEDIUM.
        Token budget: up to 256 tokens.
        Return a medium-length completion only when useful.
        Prefer complete syntactic units over using the full budget.
        Stop after a complete statement group or balanced block.
        Do not leave unfinished conditions, calls, strings, tags, or braces.
        """),
    LONG("较长", 512, """
        Completion level: HIGH.
        Token budget: up to 512 tokens.
        Return a longer completion only when the surrounding context clearly needs it.
        Prefer complete syntactic units over using the full budget.
        Stop after a natural complete boundary.
        Do not leave unfinished conditions, calls, strings, tags, or braces.
        """);

    private final String displayName;
    private final int maxTokens;
    private final String promptHint;

    AiCompletionLengthLevel(String displayName, int maxTokens, String promptHint) {
        this.displayName = displayName;
        this.maxTokens = maxTokens;
        this.promptHint = promptHint;
    }

    public String getDisplayName() {
        return displayName;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public String getPromptHint() {
        return promptHint;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
