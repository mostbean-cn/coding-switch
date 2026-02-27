package com.github.mostbean.codingswitch.service;

import com.github.mostbean.codingswitch.model.CliType;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 各 AI CLI 工具配置文件的读写服务。
 * 是所有配置操作的底层基础，封装文件系统交互。
 */
@Service(Service.Level.APP)
public final class ConfigFileService {

    private static final Logger LOG = Logger.getInstance(ConfigFileService.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static ConfigFileService getInstance() {
        return ApplicationManager.getApplication().getService(ConfigFileService.class);
    }

    // =====================================================================
    // 路径解析
    // =====================================================================

    private Path userHome() {
        return Path.of(System.getProperty("user.home"));
    }

    /** 获取 CLI 对应的主配置目录 */
    public Path getConfigDir(CliType cliType) {
        return switch (cliType) {
            case CLAUDE -> userHome().resolve(".claude");
            case CODEX -> userHome().resolve(".codex");
            case GEMINI -> userHome().resolve(".gemini");
            case OPENCODE -> userHome().resolve(".config").resolve("opencode");
        };
    }

    /**
     * 获取 Provider 配置文件路径。
     * Claude → ~/.claude/settings.json
     * Codex → ~/.codex/auth.json
     * Gemini → ~/.gemini/.env
     * OpenCode → ~/.config/opencode/opencode.json
     */
    public Path getProviderConfigPath(CliType cliType) {
        return switch (cliType) {
            case CLAUDE -> getConfigDir(cliType).resolve("settings.json");
            case CODEX -> getConfigDir(cliType).resolve("auth.json");
            case GEMINI -> getConfigDir(cliType).resolve(".env");
            case OPENCODE -> getConfigDir(cliType).resolve("opencode.json");
        };
    }

    /**
     * 获取 MCP 配置文件路径。
     * Claude → ~/.claude.json
     * Codex → ~/.codex/config.toml
     * Gemini → ~/.gemini/settings.json
     * OpenCode → ~/.config/opencode/opencode.json
     */
    public Path getMcpConfigPath(CliType cliType) {
        return switch (cliType) {
            case CLAUDE -> userHome().resolve(".claude.json");
            case CODEX -> getConfigDir(cliType).resolve("config.toml");
            case GEMINI -> getConfigDir(cliType).resolve("settings.json");
            case OPENCODE -> getConfigDir(cliType).resolve("opencode.json");
        };
    }

    /**
     * 获取提示词文件路径。
     * Claude → ~/.claude/CLAUDE.md
     * Codex → ~/.codex/AGENTS.md
     * Gemini → ~/.gemini/GEMINI.md
     * OpenCode → ~/.config/opencode/agents/ (目录)
     */
    public Path getPromptFilePath(CliType cliType) {
        return switch (cliType) {
            case CLAUDE -> getConfigDir(cliType).resolve("CLAUDE.md");
            case CODEX -> getConfigDir(cliType).resolve("AGENTS.md");
            case GEMINI -> getConfigDir(cliType).resolve("GEMINI.md");
            case OPENCODE -> getConfigDir(cliType).resolve("agents");
        };
    }

    /** Skills 安装目录（仅 Claude） */
    public Path getSkillsDir() {
        return getConfigDir(CliType.CLAUDE).resolve("skills");
    }

    // =====================================================================
    // 文件读写
    // =====================================================================

    /**
     * 安全读取文件内容，文件不存在则返回空字符串。
     */
    public String readFile(Path path) {
        try {
            if (Files.exists(path)) {
                return Files.readString(path, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            LOG.warn("Failed to read file: " + path, e);
        }
        return "";
    }

    /**
     * 原子写入文件（先写临时文件再移动，防止写入中断导致配置损坏）。
     */
    public void writeFile(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());

        Path tempFile = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.writeString(tempFile, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(tempFile, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            // 回退：非原子移动
            Files.move(tempFile, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // 清理临时文件
            Files.deleteIfExists(tempFile);
            throw e;
        }
    }

    /**
     * 读取 JSON 文件为 JsonObject。
     */
    public JsonObject readJsonFile(Path path) {
        String content = readFile(path);
        if (content.isBlank()) {
            return new JsonObject();
        }
        try {
            return JsonParser.parseString(content).getAsJsonObject();
        } catch (Exception e) {
            LOG.warn("Failed to parse JSON: " + path, e);
            return new JsonObject();
        }
    }

    /**
     * 将 JsonObject 写入文件（格式化输出）。
     */
    public void writeJsonFile(Path path, JsonObject json) throws IOException {
        writeFile(path, GSON.toJson(json));
    }

    /**
     * 判断配置目录是否存在。
     */
    public boolean configDirExists(CliType cliType) {
        return Files.isDirectory(getConfigDir(cliType));
    }
}
