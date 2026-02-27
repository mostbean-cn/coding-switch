package com.github.mostbean.codingswitch.model;

/**
 * 支持的 AI CLI 工具类型枚举。
 * 每种类型定义了对应的配置文件路径和格式。
 */
public enum CliType {

    CLAUDE("Claude Code", "claude"),
    CODEX("Codex", "codex"),
    GEMINI("Gemini CLI", "gemini"),
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
