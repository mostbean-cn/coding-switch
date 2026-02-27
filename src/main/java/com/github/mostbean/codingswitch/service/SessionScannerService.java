package com.github.mostbean.codingswitch.service;

import com.github.mostbean.codingswitch.model.CliType;
import com.github.mostbean.codingswitch.model.SessionMessage;
import com.github.mostbean.codingswitch.model.SessionMeta;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 会话扫描服务。
 * 扫描本地各 AI CLI 工具（Claude / Codex / Gemini / OpenCode）的会话文件，
 * 并提供会话列表和消息加载功能。
 */
@Service(Service.Level.APP)
public final class SessionScannerService {

    private static final Logger LOG = Logger.getInstance(SessionScannerService.class);

    public static SessionScannerService getInstance() {
        return ApplicationManager.getApplication().getService(SessionScannerService.class);
    }

    /**
     * 并行扫描所有已安装 CLI 的会话，按 lastActiveAt 倒序排列。
     */
    public List<SessionMeta> scanAllSessions() {
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            List<Future<List<SessionMeta>>> futures = new ArrayList<>();
            futures.add(executor.submit(this::scanClaudeSessions));
            futures.add(executor.submit(this::scanCodexSessions));
            futures.add(executor.submit(this::scanGeminiSessions));
            futures.add(executor.submit(this::scanOpenCodeSessions));

            List<SessionMeta> allSessions = new ArrayList<>();
            for (Future<List<SessionMeta>> future : futures) {
                try {
                    allSessions.addAll(future.get(10, TimeUnit.SECONDS));
                } catch (InterruptedException | ExecutionException | TimeoutException e) {
                    LOG.warn("Session scan task failed", e);
                }
            }

            allSessions.sort((a, b) -> Long.compare(b.getEffectiveTimestamp(), a.getEffectiveTimestamp()));
            return allSessions;
        } finally {
            executor.shutdown();
        }
    }

    /**
     * 加载指定会话的消息列表。
     */
    public List<SessionMessage> loadMessages(String providerId, String sourcePath) {
        try {
            return switch (providerId) {
                case "claude" -> loadClaudeMessages(Path.of(sourcePath));
                case "codex" -> loadCodexMessages(Path.of(sourcePath));
                case "gemini" -> loadGeminiMessages(Path.of(sourcePath));
                case "opencode" -> loadOpenCodeMessages(Path.of(sourcePath));
                default -> {
                    LOG.warn("Unsupported provider: " + providerId);
                    yield Collections.emptyList();
                }
            };
        } catch (Exception e) {
            LOG.warn("Failed to load messages for " + providerId + ": " + sourcePath, e);
            return Collections.emptyList();
        }
    }

    // =====================================================================
    // Claude 会话扫描
    // =====================================================================

    private List<SessionMeta> scanClaudeSessions() {
        ConfigFileService cfs = ConfigFileService.getInstance();
        Path projectsDir = cfs.getConfigDir(CliType.CLAUDE).resolve("projects");
        if (!Files.isDirectory(projectsDir))
            return Collections.emptyList();

        List<Path> jsonlFiles = collectFiles(projectsDir, "jsonl");
        List<SessionMeta> sessions = new ArrayList<>();
        for (Path file : jsonlFiles) {
            // 跳过 agent 会话
            String fileName = file.getFileName().toString();
            if (fileName.startsWith("agent-"))
                continue;

            SessionMeta meta = parseClaudeSession(file);
            if (meta != null)
                sessions.add(meta);
        }
        return sessions;
    }

    private SessionMeta parseClaudeSession(Path file) {
        try {
            List<String> headLines = readHeadLines(file, 10);
            List<String> tailLines = readTailLines(file, 30);

            String sessionId = null;
            String projectDir = null;
            Long createdAt = null;

            for (String line : headLines) {
                JsonObject obj = parseJsonLine(line);
                if (obj == null)
                    continue;
                if (sessionId == null && obj.has("sessionId"))
                    sessionId = obj.get("sessionId").getAsString();
                if (projectDir == null && obj.has("cwd"))
                    projectDir = obj.get("cwd").getAsString();
                if (createdAt == null && obj.has("timestamp"))
                    createdAt = parseTimestamp(obj.get("timestamp"));
            }

            Long lastActiveAt = null;
            String summary = null;
            for (int i = tailLines.size() - 1; i >= 0; i--) {
                JsonObject obj = parseJsonLine(tailLines.get(i));
                if (obj == null)
                    continue;
                if (lastActiveAt == null && obj.has("timestamp"))
                    lastActiveAt = parseTimestamp(obj.get("timestamp"));
                if (summary == null) {
                    if (obj.has("isMeta") && obj.get("isMeta").getAsBoolean())
                        continue;
                    if (obj.has("message")) {
                        String text = extractText(obj.getAsJsonObject("message").get("content"));
                        if (text != null && !text.isBlank())
                            summary = truncate(text, 160);
                    }
                }
                if (lastActiveAt != null && summary != null)
                    break;
            }

            if (sessionId == null) {
                String stem = file.getFileName().toString().replaceFirst("\\.jsonl$", "");
                sessionId = stem;
            }

            SessionMeta meta = new SessionMeta("claude", sessionId);
            meta.setTitle(pathBasename(projectDir));
            meta.setSummary(summary);
            meta.setProjectDir(projectDir);
            meta.setCreatedAt(createdAt);
            meta.setLastActiveAt(lastActiveAt);
            meta.setSourcePath(file.toAbsolutePath().toString());
            meta.setResumeCommand("claude --resume " + sessionId);
            return meta;
        } catch (Exception e) {
            LOG.debug("Failed to parse Claude session: " + file, e);
            return null;
        }
    }

    private List<SessionMessage> loadClaudeMessages(Path file) {
        List<SessionMessage> messages = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                JsonObject obj = parseJsonLine(line);
                if (obj == null)
                    continue;
                if (obj.has("isMeta") && obj.get("isMeta").getAsBoolean())
                    continue;
                if (!obj.has("message"))
                    continue;

                JsonObject message = obj.getAsJsonObject("message");
                String role = getStr(message, "role", "unknown");
                String content = extractText(message.get("content"));
                if (content == null || content.isBlank())
                    continue;

                Long ts = obj.has("timestamp") ? parseTimestamp(obj.get("timestamp")) : null;
                messages.add(new SessionMessage(role, content, ts));
            }
        } catch (IOException e) {
            LOG.warn("Failed to load Claude messages: " + file, e);
        }
        return messages;
    }

    // =====================================================================
    // Codex 会话扫描
    // =====================================================================

    private List<SessionMeta> scanCodexSessions() {
        ConfigFileService cfs = ConfigFileService.getInstance();
        Path sessionsDir = cfs.getConfigDir(CliType.CODEX).resolve("sessions");
        if (!Files.isDirectory(sessionsDir))
            return Collections.emptyList();

        List<Path> jsonlFiles = collectFiles(sessionsDir, "jsonl");
        List<SessionMeta> sessions = new ArrayList<>();
        for (Path file : jsonlFiles) {
            SessionMeta meta = parseCodexSession(file);
            if (meta != null)
                sessions.add(meta);
        }
        return sessions;
    }

    private SessionMeta parseCodexSession(Path file) {
        try {
            List<String> headLines = readHeadLines(file, 10);
            List<String> tailLines = readTailLines(file, 30);

            String sessionId = null;
            String projectDir = null;
            Long createdAt = null;

            for (String line : headLines) {
                JsonObject obj = parseJsonLine(line);
                if (obj == null)
                    continue;
                if (createdAt == null && obj.has("timestamp"))
                    createdAt = parseTimestamp(obj.get("timestamp"));
                if ("session_meta".equals(getStr(obj, "type", ""))) {
                    JsonObject payload = obj.has("payload") ? obj.getAsJsonObject("payload") : null;
                    if (payload != null) {
                        if (sessionId == null && payload.has("id"))
                            sessionId = payload.get("id").getAsString();
                        if (projectDir == null && payload.has("cwd"))
                            projectDir = payload.get("cwd").getAsString();
                    }
                }
            }

            Long lastActiveAt = null;
            String summary = null;
            for (int i = tailLines.size() - 1; i >= 0; i--) {
                JsonObject obj = parseJsonLine(tailLines.get(i));
                if (obj == null)
                    continue;
                if (lastActiveAt == null && obj.has("timestamp"))
                    lastActiveAt = parseTimestamp(obj.get("timestamp"));
                if (summary == null && "response_item".equals(getStr(obj, "type", ""))) {
                    JsonObject payload = obj.has("payload") ? obj.getAsJsonObject("payload") : null;
                    if (payload != null && "message".equals(getStr(payload, "type", ""))) {
                        String text = extractText(payload.get("content"));
                        if (text != null && !text.isBlank())
                            summary = truncate(text, 160);
                    }
                }
                if (lastActiveAt != null && summary != null)
                    break;
            }

            if (sessionId == null) {
                // 从文件名中推断 UUID
                String stem = file.getFileName().toString().replaceFirst("\\.jsonl$", "");
                if (stem.matches(".*[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-.*")) {
                    sessionId = stem;
                } else {
                    return null;
                }
            }

            SessionMeta meta = new SessionMeta("codex", sessionId);
            meta.setTitle(pathBasename(projectDir));
            meta.setSummary(summary);
            meta.setProjectDir(projectDir);
            meta.setCreatedAt(createdAt);
            meta.setLastActiveAt(lastActiveAt);
            meta.setSourcePath(file.toAbsolutePath().toString());
            meta.setResumeCommand("codex resume " + sessionId);
            return meta;
        } catch (Exception e) {
            LOG.debug("Failed to parse Codex session: " + file, e);
            return null;
        }
    }

    private List<SessionMessage> loadCodexMessages(Path file) {
        List<SessionMessage> messages = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                JsonObject obj = parseJsonLine(line);
                if (obj == null)
                    continue;
                if (!"response_item".equals(getStr(obj, "type", "")))
                    continue;
                JsonObject payload = obj.has("payload") ? obj.getAsJsonObject("payload") : null;
                if (payload == null || !"message".equals(getStr(payload, "type", "")))
                    continue;

                String role = getStr(payload, "role", "unknown");
                String content = extractText(payload.get("content"));
                if (content == null || content.isBlank())
                    continue;

                Long ts = obj.has("timestamp") ? parseTimestamp(obj.get("timestamp")) : null;
                messages.add(new SessionMessage(role, content, ts));
            }
        } catch (IOException e) {
            LOG.warn("Failed to load Codex messages: " + file, e);
        }
        return messages;
    }

    // =====================================================================
    // Gemini 会话扫描
    // =====================================================================

    private List<SessionMeta> scanGeminiSessions() {
        ConfigFileService cfs = ConfigFileService.getInstance();
        Path tmpDir = cfs.getConfigDir(CliType.GEMINI).resolve("tmp");
        if (!Files.isDirectory(tmpDir))
            return Collections.emptyList();

        List<SessionMeta> sessions = new ArrayList<>();
        try (DirectoryStream<Path> projectDirs = Files.newDirectoryStream(tmpDir)) {
            for (Path projDir : projectDirs) {
                Path chatsDir = projDir.resolve("chats");
                if (!Files.isDirectory(chatsDir))
                    continue;

                List<Path> jsonFiles = collectFiles(chatsDir, "json");
                for (Path file : jsonFiles) {
                    SessionMeta meta = parseGeminiSession(file);
                    if (meta != null)
                        sessions.add(meta);
                }
            }
        } catch (IOException e) {
            LOG.debug("Failed to scan Gemini sessions", e);
        }
        return sessions;
    }

    private SessionMeta parseGeminiSession(Path file) {
        try {
            String data = Files.readString(file, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(data).getAsJsonObject();

            String sessionId = getStr(root, "sessionId", null);
            if (sessionId == null)
                return null;

            Long createdAt = root.has("startTime") ? parseTimestamp(root.get("startTime")) : null;
            Long lastActiveAt = root.has("lastUpdated") ? parseTimestamp(root.get("lastUpdated")) : null;
            if (lastActiveAt == null)
                lastActiveAt = createdAt;

            // 从第一条用户消息中提取标题
            String title = null;
            if (root.has("messages") && root.get("messages").isJsonArray()) {
                for (JsonElement elem : root.getAsJsonArray("messages")) {
                    JsonObject msg = elem.getAsJsonObject();
                    if ("user".equals(getStr(msg, "type", ""))) {
                        String content = getStr(msg, "content", "");
                        if (!content.isBlank()) {
                            title = truncate(content, 160);
                            break;
                        }
                    }
                }
            }

            SessionMeta meta = new SessionMeta("gemini", sessionId);
            meta.setTitle(title);
            meta.setSummary(title);
            meta.setCreatedAt(createdAt);
            meta.setLastActiveAt(lastActiveAt);
            meta.setSourcePath(file.toAbsolutePath().toString());
            meta.setResumeCommand("gemini --resume " + sessionId);
            return meta;
        } catch (Exception e) {
            LOG.debug("Failed to parse Gemini session: " + file, e);
            return null;
        }
    }

    private List<SessionMessage> loadGeminiMessages(Path file) {
        List<SessionMessage> messages = new ArrayList<>();
        try {
            String data = Files.readString(file, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(data).getAsJsonObject();

            if (!root.has("messages") || !root.get("messages").isJsonArray())
                return messages;

            for (JsonElement elem : root.getAsJsonArray("messages")) {
                JsonObject msg = elem.getAsJsonObject();
                String content = getStr(msg, "content", "");
                if (content.isBlank())
                    continue;

                String type = getStr(msg, "type", "");
                String role = switch (type) {
                    case "gemini" -> "assistant";
                    case "user" -> "user";
                    default -> type;
                };

                Long ts = msg.has("timestamp") ? parseTimestamp(msg.get("timestamp")) : null;
                messages.add(new SessionMessage(role, content, ts));
            }
        } catch (Exception e) {
            LOG.warn("Failed to load Gemini messages: " + file, e);
        }
        return messages;
    }

    // =====================================================================
    // OpenCode 会话扫描
    // =====================================================================

    private List<SessionMeta> scanOpenCodeSessions() {
        Path storageDir = getOpenCodeStorageDir();
        Path sessionDir = storageDir.resolve("session");
        if (!Files.isDirectory(sessionDir))
            return Collections.emptyList();

        List<Path> jsonFiles = collectFiles(sessionDir, "json");
        List<SessionMeta> sessions = new ArrayList<>();
        for (Path file : jsonFiles) {
            SessionMeta meta = parseOpenCodeSession(storageDir, file);
            if (meta != null)
                sessions.add(meta);
        }
        return sessions;
    }

    private Path getOpenCodeStorageDir() {
        String xdgDataHome = System.getenv("XDG_DATA_HOME");
        if (xdgDataHome != null && !xdgDataHome.isBlank()) {
            return Path.of(xdgDataHome, "opencode", "storage");
        }
        return Path.of(System.getProperty("user.home"), ".local", "share", "opencode", "storage");
    }

    private SessionMeta parseOpenCodeSession(Path storageDir, Path file) {
        try {
            String data = Files.readString(file, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(data).getAsJsonObject();

            String sessionId = getStr(root, "id", null);
            if (sessionId == null)
                return null;

            String title = getStr(root, "title", null);
            String directory = getStr(root, "directory", null);

            Long createdAt = root.has("time") && root.getAsJsonObject("time").has("created")
                    ? parseTimestamp(root.getAsJsonObject("time").get("created"))
                    : null;
            Long updatedAt = root.has("time") && root.getAsJsonObject("time").has("updated")
                    ? parseTimestamp(root.getAsJsonObject("time").get("updated"))
                    : null;

            String displayTitle = title;
            if (displayTitle == null || displayTitle.isBlank()) {
                displayTitle = pathBasename(directory);
            }

            // sourcePath 指向消息目录
            Path msgDir = storageDir.resolve("message").resolve(sessionId);

            SessionMeta meta = new SessionMeta("opencode", sessionId);
            meta.setTitle(displayTitle);
            meta.setSummary(displayTitle);
            meta.setProjectDir(directory);
            meta.setCreatedAt(createdAt);
            meta.setLastActiveAt(updatedAt != null ? updatedAt : createdAt);
            meta.setSourcePath(msgDir.toAbsolutePath().toString());
            meta.setResumeCommand("opencode session resume " + sessionId);
            return meta;
        } catch (Exception e) {
            LOG.debug("Failed to parse OpenCode session: " + file, e);
            return null;
        }
    }

    private List<SessionMessage> loadOpenCodeMessages(Path msgDir) {
        if (!Files.isDirectory(msgDir))
            return Collections.emptyList();

        Path storageDir = msgDir.getParent() != null && msgDir.getParent().getParent() != null
                ? msgDir.getParent().getParent()
                : msgDir;

        List<Path> msgFiles = collectFiles(msgDir, "json");
        // 收集消息：(时间戳, 消息ID, 角色, 内容)
        record MsgEntry(long ts, String msgId, String role, String text) {
        }
        List<MsgEntry> entries = new ArrayList<>();

        for (Path msgFile : msgFiles) {
            try {
                String data = Files.readString(msgFile, StandardCharsets.UTF_8);
                JsonObject obj = JsonParser.parseString(data).getAsJsonObject();

                String msgId = getStr(obj, "id", null);
                if (msgId == null)
                    continue;

                String role = getStr(obj, "role", "unknown");
                long ts = 0;
                if (obj.has("time") && obj.getAsJsonObject("time").has("created")) {
                    Long parsed = parseTimestamp(obj.getAsJsonObject("time").get("created"));
                    if (parsed != null)
                        ts = parsed;
                }

                // 从 parts 目录收集文本
                Path partDir = storageDir.resolve("part").resolve(msgId);
                String text = collectPartsText(partDir);
                if (text.isBlank())
                    continue;

                entries.add(new MsgEntry(ts, msgId, role, text));
            } catch (Exception e) {
                LOG.debug("Failed to parse OpenCode message: " + msgFile, e);
            }
        }

        entries.sort(Comparator.comparingLong(MsgEntry::ts));
        return entries.stream()
                .map(e -> new SessionMessage(e.role(), e.text(), e.ts() > 0 ? e.ts() : null))
                .collect(Collectors.toList());
    }

    private String collectPartsText(Path partDir) {
        if (!Files.isDirectory(partDir))
            return "";

        List<Path> partFiles = collectFiles(partDir, "json");
        StringBuilder sb = new StringBuilder();
        for (Path partFile : partFiles) {
            try {
                String data = Files.readString(partFile, StandardCharsets.UTF_8);
                JsonObject obj = JsonParser.parseString(data).getAsJsonObject();
                if (!"text".equals(getStr(obj, "type", "")))
                    continue;
                String text = getStr(obj, "text", "");
                if (!text.isBlank()) {
                    if (!sb.isEmpty())
                        sb.append("\n");
                    sb.append(text);
                }
            } catch (Exception e) {
                // skip
            }
        }
        return sb.toString();
    }

    // =====================================================================
    // 通用工具方法
    // =====================================================================

    /**
     * 递归收集指定扩展名的文件。
     */
    private List<Path> collectFiles(Path dir, String extension) {
        List<Path> result = new ArrayList<>();
        if (!Files.isDirectory(dir))
            return result;

        try (Stream<Path> walker = Files.walk(dir)) {
            walker.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith("." + extension))
                    .forEach(result::add);
        } catch (IOException e) {
            LOG.debug("Failed to walk directory: " + dir, e);
        }
        return result;
    }

    /**
     * 读取文件前 N 行。
     */
    private List<String> readHeadLines(Path file, int n) {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while (lines.size() < n && (line = reader.readLine()) != null) {
                lines.add(line);
            }
        } catch (IOException e) {
            LOG.debug("Failed to read head lines: " + file, e);
        }
        return lines;
    }

    /**
     * 读取文件最后 N 行（对大文件使用 seek 优化）。
     */
    private List<String> readTailLines(Path file, int n) {
        try {
            long size = Files.size(file);
            // 小文件直接全读
            if (size < 16384) {
                List<String> all = Files.readAllLines(file, StandardCharsets.UTF_8);
                int skip = Math.max(0, all.size() - n);
                return new ArrayList<>(all.subList(skip, all.size()));
            }
            // 大文件读最后 16KB
            byte[] bytes;
            try (var channel = Files.newByteChannel(file, StandardOpenOption.READ)) {
                long seekPos = Math.max(0, size - 16384);
                channel.position(seekPos);
                java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate((int) (size - seekPos));
                channel.read(buf);
                bytes = buf.array();
            }
            String tail = new String(bytes, StandardCharsets.UTF_8);
            String[] allLines = tail.split("\n");
            // 跳过第一行（可能不完整）
            int start = allLines.length > n + 1 ? allLines.length - n : 1;
            List<String> result = new ArrayList<>();
            for (int i = Math.max(1, start); i < allLines.length; i++) {
                result.add(allLines[i]);
            }
            return result;
        } catch (IOException e) {
            LOG.debug("Failed to read tail lines: " + file, e);
            return Collections.emptyList();
        }
    }

    private JsonObject parseJsonLine(String line) {
        if (line == null || line.isBlank())
            return null;
        try {
            JsonElement elem = JsonParser.parseString(line);
            return elem.isJsonObject() ? elem.getAsJsonObject() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 解析时间戳（支持 RFC 3339 字符串和数字毫秒/秒级时间戳）。
     */
    private Long parseTimestamp(JsonElement element) {
        if (element == null || element.isJsonNull())
            return null;
        try {
            if (element.isJsonPrimitive()) {
                if (element.getAsJsonPrimitive().isString()) {
                    String raw = element.getAsString();
                    OffsetDateTime odt = OffsetDateTime.parse(raw, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
                    return odt.toInstant().toEpochMilli();
                }
                if (element.getAsJsonPrimitive().isNumber()) {
                    long val = element.getAsLong();
                    // 若小于 1e12 则认为是秒级时间戳
                    return val < 1_000_000_000_000L ? val * 1000 : val;
                }
            }
        } catch (DateTimeParseException | NumberFormatException e) {
            // 忽略
        }
        return null;
    }

    /**
     * 从 JSON content 字段中提取纯文本。
     * 支持字符串、数组（含 text/input_text/output_text 字段）、对象（含 text 字段）。
     */
    private String extractText(JsonElement content) {
        if (content == null || content.isJsonNull())
            return null;

        if (content.isJsonPrimitive()) {
            return content.getAsString();
        }

        if (content.isJsonArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonElement elem : content.getAsJsonArray()) {
                if (!elem.isJsonObject())
                    continue;
                JsonObject item = elem.getAsJsonObject();
                String text = getStr(item, "text", null);
                if (text == null)
                    text = getStr(item, "input_text", null);
                if (text == null)
                    text = getStr(item, "output_text", null);
                if (text == null && item.has("content")) {
                    text = extractText(item.get("content"));
                }
                if (text != null && !text.isBlank()) {
                    if (!sb.isEmpty())
                        sb.append("\n");
                    sb.append(text);
                }
            }
            return sb.isEmpty() ? null : sb.toString();
        }

        if (content.isJsonObject()) {
            return getStr(content.getAsJsonObject(), "text", null);
        }

        return null;
    }

    private String getStr(JsonObject obj, String key, String defaultVal) {
        if (obj.has(key) && obj.get(key).isJsonPrimitive()) {
            return obj.get(key).getAsString();
        }
        return defaultVal;
    }

    private String pathBasename(String path) {
        if (path == null || path.isBlank())
            return null;
        String normalized = path.replaceAll("[/\\\\]+$", "");
        int lastSep = Math.max(normalized.lastIndexOf('/'), normalized.lastIndexOf('\\'));
        return lastSep >= 0 ? normalized.substring(lastSep + 1) : normalized;
    }

    private String truncate(String text, int maxChars) {
        String trimmed = text.trim();
        if (trimmed.length() <= maxChars)
            return trimmed;
        return trimmed.substring(0, maxChars) + "...";
    }
}
