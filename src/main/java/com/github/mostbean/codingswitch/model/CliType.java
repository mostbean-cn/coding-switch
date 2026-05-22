package com.github.mostbean.codingswitch.model;

import com.google.gson.annotations.SerializedName;
import java.util.Arrays;

/**
 * 支持的 AI CLI 工具类型枚举。
 * 每种类型定义了对应的配置文件路径和格式。
 */
public enum CliType {

    @SerializedName(value = "CLAUDE", alternate = { "Claude Code", "Claude", "claude" })
    CLAUDE("Claude Code", "claude"),
    @SerializedName(value = "CODEX", alternate = { "Codex", "Codex CLI", "Codex (OpenAI)", "OpenAI Codex", "codex" })
    CODEX("Codex", "codex"),
    @SerializedName(value = "OPENCODE", alternate = { "OpenCode", "opencode" })
    OPENCODE("OpenCode", "opencode"),
    @SerializedName(value = "ANTIGRAVITY", alternate = { "Antigravity CLI", "Antigravity", "agy", "antigravity" })
    ANTIGRAVITY("Antigravity CLI", "agy");

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

    public static CliType fromId(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return Arrays.stream(values())
                .filter(cli -> cli.id.equalsIgnoreCase(id) || cli.matchesLegacyId(id))
                .findFirst()
                .orElse(null);
    }

    private boolean matchesLegacyId(String id) {
        return this == ANTIGRAVITY && "antigravity".equalsIgnoreCase(id);
    }

    @Override
    public String toString() {
        return displayName;
    }
}
