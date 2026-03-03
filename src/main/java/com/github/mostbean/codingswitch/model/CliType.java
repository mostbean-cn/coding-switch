package com.github.mostbean.codingswitch.model;

import com.google.gson.annotations.SerializedName;

/**
 * 支持的 AI CLI 工具类型枚举。
 * 每种类型定义了对应的配置文件路径和格式。
 */
public enum CliType {

    @SerializedName(value = "CLAUDE", alternate = { "Claude Code", "Claude", "claude" })
    CLAUDE("Claude Code", "claude"),
    @SerializedName(value = "CODEX", alternate = { "Codex", "Codex CLI", "Codex (OpenAI)", "OpenAI Codex", "codex" })
    CODEX("Codex", "codex"),
    @SerializedName(value = "GEMINI", alternate = { "Gemini CLI", "Gemini", "Google Gemini", "Gemini Code Assist",
            "gemini" })
    GEMINI("Gemini CLI", "gemini"),
    @SerializedName(value = "OPENCODE", alternate = { "OpenCode", "opencode" })
    OPENCODE("OpenCode", "opencode");

    private final String displayName;
    private final String id;

    CliType(String displayName, String id) {
        this.displayName = displayName;
        this.id = id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getId() {
        return id;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
