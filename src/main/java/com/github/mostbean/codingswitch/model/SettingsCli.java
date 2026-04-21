package com.github.mostbean.codingswitch.model;

import java.util.Arrays;
import java.util.List;

/**
 * 设置页中的 CLI 选项，仅用于版本检测与安装/更新命令展示。
 */
public enum SettingsCli {
    CLAUDE("Claude Code", "claude"),
    CODEX("Codex", "codex"),
    GEMINI("Gemini CLI", "gemini"),
    OPENCODE("OpenCode", "opencode"),
    CODEBUDDY("CodeBuddy", "codebuddy"),
    QWEN("Qwen Code", "qwen"),
    MMX("MMX", "mmx"),
    QODER("Qoder CLI", "qodercli"),
    AUGGIE("Auggie CLI", "auggie");

    private final String displayName;
    private final String id;

    SettingsCli(String displayName, String id) {
        this.displayName = displayName;
        this.id = id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getId() {
        return id;
    }

    public static SettingsCli fromId(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return Arrays.stream(values())
            .filter(cli -> cli.id.equalsIgnoreCase(id))
            .findFirst()
            .orElse(null);
    }

    public static List<SettingsCli> defaultVisibleValues() {
        return List.of(CLAUDE, CODEX, GEMINI, OPENCODE);
    }
}
