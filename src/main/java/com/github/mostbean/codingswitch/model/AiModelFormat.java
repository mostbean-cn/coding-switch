package com.github.mostbean.codingswitch.model;

/**
 * 插件内置 AI 能力使用的模型协议格式。
 */
public enum AiModelFormat {
    OPENAI_CHAT_COMPLETIONS("OpenAI Chat Completions", "https://api.openai.com/v1"),
    OPENAI_RESPONSES("OpenAI Responses", "https://api.openai.com/v1"),
    ANTHROPIC_MESSAGES("Anthropic Messages", "https://api.anthropic.com");

    private final String displayName;
    private final String defaultBaseUrl;

    AiModelFormat(String displayName, String defaultBaseUrl) {
        this.displayName = displayName;
        this.defaultBaseUrl = defaultBaseUrl;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDefaultBaseUrl() {
        return defaultBaseUrl;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
