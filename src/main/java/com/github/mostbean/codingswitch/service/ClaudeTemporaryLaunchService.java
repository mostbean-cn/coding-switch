package com.github.mostbean.codingswitch.service;

import com.github.mostbean.codingswitch.model.CliType;
import com.github.mostbean.codingswitch.model.Provider;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.util.concurrency.AppExecutorUtil;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.plugins.terminal.TerminalOptionsProvider;

/**
 * 将 Claude Provider 转换为一次性终端启动参数，不写入 live 配置文件。
 */
public final class ClaudeTemporaryLaunchService {

    private static final Gson GSON = new GsonBuilder().create();
    private static final String CLAUDE_COMMAND = "claude";
    private static final String TEMP_DIRECTORY_NAME = "coding-switch";
    private static final int SETTINGS_FILE_CLEANUP_DELAY_SECONDS = 10;
    private static final Pattern ANSI_ESCAPE_PATTERN = Pattern.compile("\\u001B\\[[;?0-9]*[ -/]*[@-~]");
    private static final Pattern CONTROL_CHAR_PATTERN = Pattern.compile("[\\p{Cntrl}&&[^\r\n\t]]");
    private static final Set<PosixFilePermission> OWNER_READ_WRITE = EnumSet.of(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE
    );
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
        clearManagedEnvironment(env);
        copyEnvConfig(config, env);
        copySupportedSettings(config, env);
        String settingsJson = GSON.toJson(buildCommandLineSettings(config));
        Path settingsPath = createTemporarySettingsFile(settingsJson);
        boolean dangerous = isTrue(config, "dangerouslySkipPermissions");
        String command = buildCommand(settingsPath, dangerous);

        return new LaunchRequest(command, Collections.unmodifiableMap(env), settingsPath);
    }

    private static void clearManagedEnvironment(Map<String, String> env) {
        for (String key : MANAGED_ENV_KEYS) {
            env.put(key, "");
        }
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
            text = sanitizeSettingsString(text);
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
                commandEnv.addProperty(key, toSanitizedEnvString(configuredEnv.get(key)));
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
            JsonElement value = source.get(key);
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                target.addProperty(key, sanitizeSettingsString(value.getAsString()));
            } else {
                target.add(key, value.deepCopy());
            }
        }
    }

    private static String buildCommand(Path settingsPath, boolean dangerouslySkipPermissions) {
        String dangerousFlag = dangerouslySkipPermissions ? " --dangerously-skip-permissions" : "";
        return switch (resolveShellKind()) {
            case POWERSHELL -> {
                String quotedPath = quotePowerShell(settingsPath.toString());
                yield CLAUDE_COMMAND + " --settings " + quotedPath + dangerousFlag
                    + "; $__codingSwitchExit=$LASTEXITCODE"
                    + "; Remove-Item -LiteralPath " + quotedPath + " -Force -ErrorAction SilentlyContinue"
                    + "; if ($__codingSwitchExit -ne 0) { Write-Host ('Claude Code exited with code ' + $__codingSwitchExit) }";
            }
            case CMD -> {
                String quotedPath = quoteCmd(settingsPath.toString());
                yield CLAUDE_COMMAND + " --settings " + quotedPath + dangerousFlag
                    + " & del /f /q " + quotedPath + " >nul 2>nul";
            }
            case POSIX -> {
                String quotedPath = quotePosix(toPosixFriendlyPath(settingsPath));
                yield CLAUDE_COMMAND + " --settings " + quotedPath + dangerousFlag
                    + "; __coding_switch_exit=$?"
                    + "; rm -f -- " + quotedPath
                    + "; if [ $__coding_switch_exit -ne 0 ]; then echo \"Claude Code exited with code $__coding_switch_exit\"; fi";
            }
            case FISH -> {
                String quotedPath = quotePosix(toPosixFriendlyPath(settingsPath));
                yield CLAUDE_COMMAND + " --settings " + quotedPath + dangerousFlag
                    + "; set __coding_switch_exit $status"
                    + "; rm -f -- " + quotedPath
                    + "; if test $__coding_switch_exit -ne 0; echo \"Claude Code exited with code $__coding_switch_exit\"; end";
            }
            case WSL -> {
                String quotedPath = quotePosix(toWslPath(settingsPath));
                yield CLAUDE_COMMAND + " --settings " + quotedPath + dangerousFlag
                    + "; __coding_switch_exit=$?"
                    + "; rm -f -- " + quotedPath
                    + "; if [ $__coding_switch_exit -ne 0 ]; then echo \"Claude Code exited with code $__coding_switch_exit\"; fi";
            }
        };
    }

    private static Path createTemporarySettingsFile(String settingsJson) {
        Path path = null;
        try {
            path = createOwnerOnlyTempFile();
            Files.writeString(path, settingsJson, StandardCharsets.UTF_8);
            restrictOwnerAccess(path);
            path.toFile().deleteOnExit();
            return path;
        } catch (IOException e) {
            deleteQuietly(path);
            throw new UncheckedIOException("Failed to create temporary Claude settings file", e);
        } catch (RuntimeException e) {
            deleteQuietly(path);
            throw e;
        }
    }

    private static Path createOwnerOnlyTempFile() throws IOException {
        Path directory = ensureTemporaryDirectory();
        try {
            FileAttribute<Set<PosixFilePermission>> permissions =
                PosixFilePermissions.asFileAttribute(OWNER_READ_WRITE);
            return Files.createTempFile(directory, "claude-", ".json", permissions);
        } catch (UnsupportedOperationException ignored) {
            return Files.createTempFile(directory, "claude-", ".json");
        }
    }

    private static Path ensureTemporaryDirectory() throws IOException {
        Path directory = Path.of(System.getProperty("java.io.tmpdir"), TEMP_DIRECTORY_NAME);
        Files.createDirectories(directory);
        restrictOwnerAccess(directory);
        return directory;
    }

    private static void restrictOwnerAccess(Path path) throws IOException {
        try {
            Files.setPosixFilePermissions(path, OWNER_READ_WRITE);
        } catch (UnsupportedOperationException ignored) {
            // Windows 没有 POSIX 权限；系统临时目录默认使用当前用户 ACL。
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 临时文件稍后仍会由 deleteOnExit 或 shell 清理兜底。
        }
    }

    private static String quotePowerShell(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private static String quoteCmd(String value) {
        return "\"" + value.replace("%", "%%").replace("\"", "\\\"") + "\"";
    }

    private static String quotePosix(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static String toPosixFriendlyPath(Path path) {
        String text = path.toString();
        return SystemInfo.isWindows ? text.replace('\\', '/') : text;
    }

    private static String toWslPath(Path path) {
        String text = path.toAbsolutePath().toString().replace('\\', '/');
        if (text.length() >= 3 && text.charAt(1) == ':' && text.charAt(2) == '/') {
            char drive = Character.toLowerCase(text.charAt(0));
            return "/mnt/" + drive + text.substring(2);
        }
        return text;
    }

    private static ShellKind resolveShellKind() {
        try {
            String shellPath = TerminalOptionsProvider.getInstance().getShellPath();
            String lower = shellPath == null ? "" : shellPath.toLowerCase();
            if (lower.contains("fish")) {
                return ShellKind.FISH;
            }
            if (!SystemInfo.isWindows) {
                return ShellKind.POSIX;
            }
            if (lower.contains("wsl")) {
                return ShellKind.WSL;
            }
            if (lower.contains("cmd")) {
                return ShellKind.CMD;
            }
            if (lower.contains("bash") || lower.contains("zsh") || lower.contains("sh.exe")) {
                return ShellKind.POSIX;
            }
        } catch (RuntimeException ignored) {
            // 单元测试或极早期初始化阶段可能拿不到 Terminal 设置，回退到当前系统默认 shell 类型。
        }
        if (!SystemInfo.isWindows) {
            return ShellKind.POSIX;
        }
        return ShellKind.POWERSHELL;
    }

    private enum ShellKind {
        POWERSHELL,
        CMD,
        POSIX,
        FISH,
        WSL
    }

    private static void copySupportedSettings(JsonObject config, Map<String, String> env) {
        if (config == null) {
            return;
        }

        String effortLevel = getString(config, "effortLevel");
        if (!effortLevel.isBlank()) {
            env.put("CLAUDE_CODE_EFFORT_LEVEL", sanitizeSettingsString(effortLevel));
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
        return sanitizeSettingsString(config.get(key).getAsString()).trim();
    }

    private static boolean isTrue(JsonObject config, String key) {
        return config != null
            && config.has(key)
            && !config.get(key).isJsonNull()
            && config.get(key).getAsBoolean();
    }

    private static String sanitizeSettingsString(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String withoutAnsi = ANSI_ESCAPE_PATTERN.matcher(value).replaceAll("");
        return CONTROL_CHAR_PATTERN.matcher(withoutAnsi).replaceAll("");
    }

    private static String toSanitizedEnvString(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return "";
        }
        String text = value.isJsonPrimitive()
            ? value.getAsString()
            : value.toString();
        return sanitizeSettingsString(text);
    }

    public record LaunchRequest(String command, Map<String, String> environment, Path temporarySettingsPath) {
        public void scheduleTemporarySettingsFileDeletion() {
            AppExecutorUtil.getAppScheduledExecutorService().schedule(
                this::deleteTemporarySettingsFile,
                SETTINGS_FILE_CLEANUP_DELAY_SECONDS,
                TimeUnit.SECONDS
            );
        }

        public void deleteTemporarySettingsFile() {
            deleteQuietly(temporarySettingsPath);
        }
    }
}
