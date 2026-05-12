package com.github.mostbean.codingswitch.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.intellij.openapi.diagnostic.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 插件用户级共享存储。
 */
public final class PluginDataStorage {

    private static final Logger LOG = Logger.getInstance(PluginDataStorage.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private PluginDataStorage() {
    }

    public static Path getUserSharedRootDir() {
        String userHome = System.getProperty("user.home");
        String osName = System.getProperty("os.name", "").toLowerCase();

        if (osName.contains("win")) {
            String appData = System.getenv("APPDATA");
            if (appData != null && !appData.isBlank()) {
                return Path.of(appData).resolve("coding-switch");
            }
        }

        if (osName.contains("mac")) {
            return Path.of(userHome, "Library", "Application Support", "coding-switch");
        }

        String xdgConfigHome = System.getenv("XDG_CONFIG_HOME");
        if (xdgConfigHome != null && !xdgConfigHome.isBlank()) {
            return Path.of(xdgConfigHome).resolve("coding-switch");
        }
        return Path.of(userHome, ".config", "coding-switch");
    }

    public static Path getSharedSettingsPath() {
        return getUserSharedRootDir().resolve("settings.json");
    }

    public static Path getSharedProvidersPath() {
        return getUserSharedRootDir().resolve("providers.json");
    }

    public static Path getSharedPromptsPath() {
        return getUserSharedRootDir().resolve("prompts.json");
    }

    public static Path getSharedSkillsPath() {
        return getUserSharedRootDir().resolve("skills.json");
    }

    public static Path getSharedMcpPath() {
        return getUserSharedRootDir().resolve("mcp.json");
    }

    public static Path getSharedAiFeaturesPath() {
        return getUserSharedRootDir().resolve("ai-features.json");
    }

    public static String readJsonText(Path path, String defaultValue) {
        try {
            if (!Files.exists(path)) {
                return defaultValue;
            }
            String raw = Files.readString(path, StandardCharsets.UTF_8);
            return raw == null || raw.isBlank() ? defaultValue : raw;
        } catch (IOException e) {
            LOG.warn("Failed to read shared state: " + path, e);
            return defaultValue;
        }
    }

    public static void writeJsonText(Path path, String content) {
        try {
            writeFile(path, content == null || content.isBlank() ? "[]" : content);
        } catch (IOException e) {
            LOG.warn("Failed to write shared state: " + path, e);
        }
    }

    public static <T> T readJson(Path path, Class<T> clazz, T defaultValue) {
        try {
            if (!Files.exists(path)) {
                return defaultValue;
            }
            String raw = Files.readString(path, StandardCharsets.UTF_8);
            if (raw == null || raw.isBlank()) {
                return defaultValue;
            }
            T parsed = GSON.fromJson(raw, clazz);
            return parsed != null ? parsed : defaultValue;
        } catch (Exception e) {
            LOG.warn("Failed to parse shared state: " + path, e);
            return defaultValue;
        }
    }

    public static void writeJson(Path path, Object value) {
        try {
            writeFile(path, GSON.toJson(value));
        } catch (IOException e) {
            LOG.warn("Failed to write shared json: " + path, e);
        }
    }

    private static void writeFile(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Path tempFile = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.writeString(tempFile, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(tempFile, path,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tempFile, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.deleteIfExists(tempFile);
            throw e;
        }
    }
}
