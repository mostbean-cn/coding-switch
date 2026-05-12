package com.github.mostbean.codingswitch.model;

public enum AiCompletionLengthLevel {
    SINGLE_LINE("单行", 32, "Return exactly one concise line."),
    SHORT("较短", 80, "Return a short completion."),
    MEDIUM("中等", 160, "Return a medium-length completion when useful."),
    LONG("较长", 320, "Return a longer completion when the context clearly needs it.");

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
