package com.github.mostbean.codingswitch.service;

import com.github.mostbean.codingswitch.model.CliType;
import com.github.mostbean.codingswitch.model.Provider;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.util.SystemInfo;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.plugins.terminal.TerminalOptionsProvider;

/**
 * 将 Claude Provider 转换为一次性终端启动参数，不写入 live 配置文件。
 */
public final class ClaudeTemporaryLaunchService {

    private static final Gson GSON = new GsonBuilder().create();
    private static final String CLAUDE_COMMAND = "claude";
    private static final String SETTINGS_ENV_NAME = "CODING_SWITCH_CLAUDE_SETTINGS";
    private static final String[] MANAGED_ENV_KEYS = {
        "ANTHROPIC_AUTH_TOKEN",
        "ANTHROPIC_API_KEY",
        "ANTHROPIC_BASE_URL",
        "ANTHROPIC_MODEL",
        "ANTHROPIC_DEFAULT_HAIKU_MODEL",
        "ANTHROPIC_DEFAULT_SONNET_MODEL",
        "ANTHROPIC_DEFAULT_OPUS_MODEL",
        "CLAUDE_CODE_AUTO_COMPACT_WINDOW",
        "CLAUDE_CODE_EFFORT_LEVEL",
        "CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS",
        "ENABLE_TOOL_SEARCH",
        "DISABLE_AUTOUPDATER",
        "CLAUDE_CODE_NO_FLICKER",
        "CLAUDE_CODE_DISABLE_MOUSE"
    };

    private ClaudeTemporaryLaunchService() {}

    public static @NotNull LaunchRequest buildLaunchRequest(@NotNull Provider provider) {
        if (provider.getCliType() != CliType.CLAUDE) {
            throw new IllegalArgumentException("Only Claude Code providers support temporary launch");
        }

        JsonObject config = provider.getSettingsConfig();
        Map<String, String> env = new LinkedHashMap<>();
        copyEnvConfig(config, env);
        copySupportedSettings(config, env);
        env.put(SETTINGS_ENV_NAME, GSON.toJson(buildCommandLineSettings(config)));

        String command = CLAUDE_COMMAND + " --settings " + quotedSettingsEnvReference();
        if (isTrue(config, "dangerouslySkipPermissions")) {
            command += " --dangerously-skip-permissions";
        }

        return new LaunchRequest(command, Collections.unmodifiableMap(env));
    }

    private static void copyEnvConfig(JsonObject config, Map<String, String> env) {
        if (config == null || !config.has("env") || !config.get("env").isJsonObject()) {
            return;
        }

        JsonObject configuredEnv = config.getAsJsonObject("env");
        for (String key : configuredEnv.keySet()) {
            JsonElement value = configuredEnv.get(key);
            if (key == null || key.isBlank() || value == null || value.isJsonNull()) {
                continue;
            }

            String text = value.isJsonPrimitive()
                ? value.getAsString()
                : value.toString();
            if (!text.isBlank()) {
                env.put(key, text);
            }
        }
    }

    private static JsonObject buildCommandLineSettings(JsonObject config) {
        JsonObject settings = new JsonObject();
        if (config == null) {
            return settings;
        }

        JsonObject commandEnv = new JsonObject();
        for (String key : MANAGED_ENV_KEYS) {
            commandEnv.addProperty(key, "");
        }
        if (config.has("env") && config.get("env").isJsonObject()) {
            JsonObject configuredEnv = config.getAsJsonObject("env");
            for (String key : configuredEnv.keySet()) {
                commandEnv.add(key, configuredEnv.get(key).deepCopy());
            }
        }
        settings.add("env", commandEnv);
        copySetting(config, settings, "effortLevel");
        copySetting(config, settings, "alwaysThinkingEnabled");
        copySetting(config, settings, "dangerouslySkipPermissions");
        copySetting(config, settings, "skipDangerousModePermissionPrompt");
        return settings;
    }

    private static void copySetting(JsonObject source, JsonObject target, String key) {
        if (source.has(key) && !source.get(key).isJsonNull()) {
            target.add(key, source.get(key).deepCopy());
        }
    }

    private static String quotedSettingsEnvReference() {
        String reference = switch (resolveShellKind()) {
            case POWERSHELL -> "$env:" + SETTINGS_ENV_NAME;
            case CMD -> "%" + SETTINGS_ENV_NAME + "%";
            case POSIX -> "$" + SETTINGS_ENV_NAME;
        };
        return "\"" + reference + "\"";
    }

    private static ShellKind resolveShellKind() {
        if (!SystemInfo.isWindows) {
            return ShellKind.POSIX;
        }
        try {
            String shellPath = TerminalOptionsProvider.getInstance().getShellPath();
            String lower = shellPath == null ? "" : shellPath.toLowerCase();
            if (lower.contains("cmd")) {
                return ShellKind.CMD;
            }
            if (lower.contains("bash") || lower.contains("zsh") || lower.contains("sh.exe")) {
                return ShellKind.POSIX;
            }
        } catch (RuntimeException ignored) {
            // 单元测试或极早期初始化阶段可能拿不到 Terminal 设置，按 Windows 默认 PowerShell 处理。
        }
        return ShellKind.POWERSHELL;
    }

    private enum ShellKind {
        POWERSHELL,
        CMD,
        POSIX
    }

    private static void copySupportedSettings(JsonObject config, Map<String, String> env) {
        if (config == null) {
            return;
        }

        String effortLevel = getString(config, "effortLevel");
        if (!effortLevel.isBlank()) {
            env.put("CLAUDE_CODE_EFFORT_LEVEL", effortLevel);
        }

        if (config.has("alwaysThinkingEnabled")
            && !config.get("alwaysThinkingEnabled").isJsonNull()
            && !config.get("alwaysThinkingEnabled").getAsBoolean()) {
            env.put("CLAUDE_CODE_DISABLE_THINKING", "1");
        }
    }

    private static String getString(JsonObject config, String key) {
        if (!config.has(key) || config.get(key).isJsonNull()) {
            return "";
        }
        return config.get(key).getAsString().trim();
    }

    private static boolean isTrue(JsonObject config, String key) {
        return config != null
            && config.has(key)
            && !config.get(key).isJsonNull()
            && config.get(key).getAsBoolean();
    }

    public record LaunchRequest(String command, Map<String, String> environment) {}
}
