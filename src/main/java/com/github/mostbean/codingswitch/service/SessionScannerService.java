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
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.*;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 会话扫描服务。
 * 扫描本地各 AI CLI 工具（Claude / Codex / OpenCode）的会话文件，
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
        ExecutorService executor = Executors.newFixedThreadPool(5);
        try {
            List<Future<List<SessionMeta>>> futures = new ArrayList<>();
            futures.add(executor.submit(this::scanClaudeSessions));
            futures.add(executor.submit(this::scanCodexSessions));
            futures.add(executor.submit(this::scanOpenCodeSessions));
            futures.add(executor.submit(this::scanAntigravitySessions));

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
            List<SessionMessage> messages = switch (providerId) {
                case "claude" -> loadClaudeMessages(Path.of(sourcePath));
                case "codex" -> loadCodexMessages(Path.of(sourcePath));
                case "opencode" -> loadOpenCodeMessages(Path.of(sourcePath));
                case "agy", "antigravity" -> loadAntigravityMessages(Path.of(sourcePath));
                default -> {
                    LOG.warn("Unsupported provider: " + providerId);
                    yield Collections.emptyList();
                }
            };
            return postProcessMessages(messages);
        } catch (Exception e) {
            LOG.warn("Failed to load messages for " + providerId + ": " + sourcePath, e);
            return Collections.emptyList();
        }
    }

    /**
     * 当前版本支持删除全部已扫描 CLI 的会话文件。
     */
    public boolean supportsDelete(SessionMeta session) {
        if (session == null || session.getProviderId() == null) {
            return false;
        }
        return switch (session.getProviderId()) {
            case "claude", "codex", "opencode", "agy", "antigravity" -> true;
            default -> false;
        };
    }

    /**
     * 删除指定会话在本地存储中的文件。
     */
    public void deleteSession(SessionMeta session) throws IOException {
        if (session == null) {
            throw new IOException("会话不存在");
        }
        if (!supportsDelete(session)) {
            throw new UnsupportedOperationException("当前 CLI 暂不支持删除会话");
        }
        switch (session.getProviderId()) {
            case "claude", "codex", "agy", "antigravity" -> {
                String deletePath = session.getDeletePath();
                if (deletePath == null || deletePath.isBlank()) {
                    throw new IOException("缺少会话删除路径");
                }
                deleteRecursively(Path.of(deletePath));
            }
            case "opencode" -> deleteOpenCodeSession(session);
            default -> throw new UnsupportedOperationException("当前 CLI 暂不支持删除会话");
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
            meta.setDeletePath(file.toAbsolutePath().toString());
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
                String role = normalizeClaudeRole(message);
                // 跳过工具调用结果消息，对回顾对话无价值
                if ("tool".equalsIgnoreCase(role))
                    continue;
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
            meta.setDeletePath(file.toAbsolutePath().toString());
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
            meta.setDeletePath(file.toAbsolutePath().toString());
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

    private void deleteOpenCodeSession(SessionMeta session) throws IOException {
        String sourcePath = session.getSourcePath();
        String deletePath = session.getDeletePath();
        if (sourcePath == null || sourcePath.isBlank()) {
            throw new IOException("缺少 OpenCode 消息目录路径");
        }
        if (deletePath == null || deletePath.isBlank()) {
            throw new IOException("缺少 OpenCode 会话文件路径");
        }

        Path messageDir = Path.of(sourcePath);
        Path sessionFile = Path.of(deletePath);
        Path storageDir = messageDir.getParent() != null && messageDir.getParent().getParent() != null
                ? messageDir.getParent().getParent()
                : null;

        Set<String> msgIds = collectOpenCodeMessageIds(messageDir);
        List<Path> partDirs = new ArrayList<>();
        if (storageDir != null) {
            for (String msgId : msgIds) {
                if (msgId != null && !msgId.isBlank()) {
                    partDirs.add(storageDir.resolve("part").resolve(msgId));
                }
            }
        }

        for (Path partDir : partDirs) {
            deleteRecursively(partDir);
        }
        deleteRecursively(messageDir);
        deleteRecursively(sessionFile);
    }

    private Set<String> collectOpenCodeMessageIds(Path messageDir) {
        if (!Files.isDirectory(messageDir)) {
            return Collections.emptySet();
        }
        Set<String> ids = new LinkedHashSet<>();
        for (Path msgFile : collectFiles(messageDir, "json")) {
            try {
                String data = Files.readString(msgFile, StandardCharsets.UTF_8);
                JsonObject obj = JsonParser.parseString(data).getAsJsonObject();
                String msgId = getStr(obj, "id", null);
                if (msgId != null && !msgId.isBlank()) {
                    ids.add(msgId);
                }
            } catch (Exception e) {
                LOG.debug("Failed to parse OpenCode message id: " + msgFile, e);
            }
        }
        return ids;
    }

    private void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        IOException last = null;
        for (int i = 0; i < 3; i++) {
            try {
                deleteRecursivelyOnce(path);
                return;
            } catch (IOException e) {
                last = e;
                sleepSilently(120);
            }
        }
        String message = "删除失败，文件可能被占用: " + path;
        if (last != null && last.getMessage() != null && !last.getMessage().isBlank()) {
            message = message + "\n" + last.getMessage();
        }
        throw new IOException(message, last);
    }

    private static void deleteRecursivelyOnce(Path root) throws IOException {
        if (Files.isRegularFile(root)) {
            deleteOnePath(root);
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                deleteOnePath(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                if (Files.exists(file)) {
                    deleteOnePath(file);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null && !(exc instanceof AccessDeniedException) && !(exc instanceof FileSystemException)) {
                    throw exc;
                }
                deleteOnePath(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteOnePath(Path path) throws IOException {
        clearReadOnly(path);
        try {
            Files.deleteIfExists(path);
        } catch (FileSystemException e) {
            clearReadOnly(path);
            Files.deleteIfExists(path);
        }
    }

    private static void clearReadOnly(Path path) {
        try {
            DosFileAttributeView view = Files.getFileAttributeView(path, DosFileAttributeView.class);
            if (view != null && view.readAttributes().isReadOnly()) {
                view.setReadOnly(false);
            }
        } catch (IOException ignored) {
            // ignore
        }
    }

    private static void sleepSilently(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
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
                String type = getStr(item, "type", "").toLowerCase();
                // 跳过工具调用请求和思考过程，只保留有意义的文本
                if ("tool_use".equals(type) || "thinking".equals(type))
                    continue;
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

    private String normalizeClaudeRole(JsonObject message) {
        String role = getStr(message, "role", "unknown");
        if (!"user".equalsIgnoreCase(role)) {
            return role;
        }

        if (message.has("content") && message.get("content").isJsonArray()) {
            for (JsonElement elem : message.getAsJsonArray("content")) {
                if (!elem.isJsonObject()) {
                    continue;
                }
                JsonObject item = elem.getAsJsonObject();
                String type = getStr(item, "type", "").toLowerCase();
                if ("tool_result".equals(type)) {
                    return "tool";
                }
            }
        }

        return role;
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

    // =====================================================================
    // Antigravity 会话扫描与解析
    // =====================================================================

    private List<SessionMeta> scanAntigravitySessions() {
        List<SessionMeta> sessions = new ArrayList<>();

        // 会话正文自 2026 年 6 月起存于 conversations/<id>.db（SQLite），
        // 旧版会话为加密的 conversations/<id>.pb；brain/<id> 仅保留工件（用于推断项目目录）。
        Path cliRoot = Path.of(System.getProperty("user.home"), ".gemini", "antigravity-cli");
        scanAntigravityConversations(cliRoot.resolve("conversations"), cliRoot.resolve("brain"), "CLI", sessions);

        return sessions;
    }

    private void scanAntigravityConversations(
            Path conversationsDir, Path brainDir, String sourceLabel, List<SessionMeta> resultList) {
        if (!Files.isDirectory(conversationsDir)) {
            return;
        }

        try (Stream<Path> files = Files.list(conversationsDir)) {
            List<Path> convFiles = files.filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.endsWith(".db") || name.endsWith(".pb");
                    })
                    .toList();
            for (Path file : convFiles) {
                SessionMeta meta = parseAntigravitySession(file, brainDir, sourceLabel);
                if (meta != null) {
                    resultList.add(meta);
                }
            }
        } catch (IOException e) {
            LOG.warn("Failed to scan Antigravity conversations in: " + conversationsDir, e);
        }
    }

    private SessionMeta parseAntigravitySession(Path convFile, Path brainDir, String sourceLabel) {
        try {
            String fileName = convFile.getFileName().toString();
            boolean encrypted = fileName.endsWith(".pb");
            String sessionId = fileName.replaceFirst("\\.(db|pb)$", "");

            String summary = null;
            if (!encrypted) {
                String firstUser = AntigravityDbParser.readFirstUserText(convFile);
                if (firstUser != null && !firstUser.isBlank()) {
                    String cleaned = cleanAntigravityText(firstUser);
                    if (cleaned != null && !cleaned.isBlank()) {
                        summary = truncate(cleaned, 160);
                    }
                }
            }

            Long createdAt = null;
            Long lastActiveAt = null;
            try {
                BasicFileAttributes attrs = Files.readAttributes(convFile, BasicFileAttributes.class);
                createdAt = attrs.creationTime().toMillis();
                lastActiveAt = attrs.lastModifiedTime().toMillis();
            } catch (IOException ignored) {}

            String projectDir = null;
            if (brainDir != null) {
                Path sessionBrainDir = brainDir.resolve(sessionId);
                if (Files.isDirectory(sessionBrainDir)) {
                    projectDir = inferProjectDirFromArtifacts(sessionBrainDir);
                }
            }

            // 标题优先级：1. 首条用户输入摘要  2. 项目目录名  3. 会话 ID 前 8 位
            String title = null;
            if (summary != null && !summary.isBlank() && !summary.startsWith("旧版加密会话") && !summary.startsWith("Antigravity CLI 会话")) {
                title = truncate(summary, 80);
            }
            if (title == null && projectDir != null) {
                title = pathBasename(projectDir);
            }
            if (title == null) {
                title = sessionId.length() > 8 ? sessionId.substring(0, 8) : sessionId;
            }

            // 详细摘要用于副标题显示
            String detailedSummary = summary;
            if (detailedSummary == null || detailedSummary.isBlank()) {
                detailedSummary = encrypted
                        ? "旧版加密会话（.pb），无法预览内容"
                        : "Antigravity CLI 会话: " + sessionId;
            }

            SessionMeta meta = new SessionMeta("agy", sessionId);
            meta.setTitle(title);
            meta.setSummary(detailedSummary);
            meta.setProjectDir(projectDir);
            meta.setCreatedAt(createdAt);
            meta.setLastActiveAt(lastActiveAt != null ? lastActiveAt : createdAt);
            meta.setSourcePath(convFile.toAbsolutePath().toString());
            meta.setDeletePath(convFile.toAbsolutePath().toString());
            meta.setResumeCommand("agy --conversation " + sessionId);
            meta.setClientSource(sourceLabel);
            return meta;
        } catch (Exception e) {
            LOG.debug("Failed to parse Antigravity session: " + convFile, e);
            return null;
        }
    }

    private String findProjectDirInJson(JsonObject obj) {
        if (obj == null) return null;
        if (obj.has("tool_calls") && obj.get("tool_calls").isJsonArray()) {
            for (JsonElement tcElem : obj.getAsJsonArray("tool_calls")) {
                if (!tcElem.isJsonObject()) continue;
                JsonObject tc = tcElem.getAsJsonObject();
                if (tc.has("args") && tc.get("args").isJsonObject()) {
                    JsonObject args = tc.getAsJsonObject("args");
                    if (args.has("DirectoryPath")) {
                        String pathVal = cleanJsonStringQuotes(args.get("DirectoryPath").getAsString());
                        if (isValidProjectDirectory(pathVal)) {
                            return pathVal;
                        }
                    }
                    if (args.has("AbsolutePath")) {
                        String pathVal = cleanJsonStringQuotes(args.get("AbsolutePath").getAsString());
                        try {
                            Path parent = Path.of(pathVal).getParent();
                            if (parent != null && isValidProjectDirectory(parent.toString())) {
                                return parent.toString();
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }
        }
        if (obj.has("content")) {
            String content = obj.get("content").getAsString();
            String pathVal = findPathInText(content);
            if (pathVal != null) {
                return pathVal;
            }
        }
        return null;
    }

    private String cleanJsonStringQuotes(String val) {
        if (val == null) return "";
        String trimmed = val.trim();
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed.replace("\\\\", "\\").replace("\\", "/");
    }

    private boolean isValidProjectDirectory(String path) {
        if (path == null || path.isBlank()) return false;
        try {
            Path p = Path.of(path);
            return Files.isDirectory(p);
        } catch (Exception e) {
            return false;
        }
    }

    private String findPathInText(String text) {
        if (text == null || text.isBlank()) return null;

        // 优先匹配带双引号的路径（处理空格）
        Pattern quotedPattern = Pattern.compile("\"([a-zA-Z]:[\\\\/][^\"/]+[^\"/]*|/[^\"/]+/[^\"/]*)\"");
        Matcher quotedMatcher = quotedPattern.matcher(text);
        if (quotedMatcher.find()) {
            String matched = quotedMatcher.group(1);
            String path = tryGetValidDirPath(matched);
            if (path != null) return path;
        }

        // 兜底匹配普通路径（不带空格）
        // Windows: C:\path, Unix: /path/to or /tmp
        Pattern pattern = Pattern.compile("([a-zA-Z]:[\\\\/][^\\s\"']+|/[^\\s\"'<>|*]+)");
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            String matched = matcher.group(1);
            String path = tryGetValidDirPath(matched);
            if (path != null) return path;
        }
        return null;
    }

    private String tryGetValidDirPath(String matched) {
        try {
            Path path = Path.of(matched);
            if (Files.isDirectory(path)) {
                return path.toAbsolutePath().toString();
            } else if (Files.isRegularFile(path)) {
                Path parent = path.getParent();
                if (parent != null) {
                    return parent.toAbsolutePath().toString();
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String inferProjectDirFromArtifacts(Path sessionDir) {
        Path ipFile = sessionDir.resolve("implementation_plan.md");
        if (Files.exists(ipFile)) {
            try {
                String content = Files.readString(ipFile, StandardCharsets.UTF_8);
                String path = findPathInText(content);
                if (path != null) return path;
            } catch (IOException ignored) {}
        }
        return null;
    }

    private String cleanAntigravityText(String content) {
        if (content == null) return "";
        // 仅清理特定的 Antigravity 元数据标签，避免使用通配符 <[^>]+> 误伤代码逻辑（如 a < b）
        String cleaned = content.replaceAll("(?s)<USER_REQUEST>.*?</USER_REQUEST>", "")
                .replaceAll("(?s)<ADDITIONAL_METADATA>.*?</ADDITIONAL_METADATA>", "")
                .replaceAll("(?s)<user_information>.*?</user_information>", "")
                .replaceAll("(?s)<user_rules>.*?</user_rules>", "")
                .replaceAll("(?s)<subagents>.*?</subagents>", "")
                .replaceAll("(?s)<artifacts>.*?</artifacts>", "")
                .replaceAll("(?s)<planning_mode>.*?</planning_mode>", "")
                .replaceAll("(?s)<identity>.*?</identity>", "")
                .replaceAll("(?s)<messaging>.*?</messaging>", "")
                .replaceAll("(?s)<thinking_mode>.*?</thinking_mode>", "")
                .replaceAll("(?s)<thinking>.*?</thinking>", "")
                .trim();
        // 移除纯工具调用格式的行
        cleaned = cleaned.replaceAll("(?m)^\\s*🛠️\\s*正在执行工具:.*$", "").trim();
        return cleaned.isEmpty() ? content.trim() : cleaned;
    }

    /**
     * 消息后处理：压缩连续空行、去除首尾空白、过滤纯噪声消息。
     */
    private List<SessionMessage> postProcessMessages(List<SessionMessage> messages) {
        List<SessionMessage> result = new ArrayList<>();
        for (SessionMessage msg : messages) {
            String content = msg.getContent();
            if (content == null) continue;
            // 压缩连续空行为单个换行
            content = content.replaceAll("\\n{3,}", "\n\n").trim();
            if (content.isEmpty()) continue;
            result.add(new SessionMessage(msg.getRole(), content, msg.getTimestamp()));
        }
        return result;
    }

    private List<SessionMessage> loadAntigravityMessages(Path file) {
        String fileName = file.getFileName().toString();

        // 旧版加密会话（.pb）无法解析内容
        if (fileName.endsWith(".pb")) {
            List<SessionMessage> messages = new ArrayList<>();
            String sessionId = fileName.replaceFirst("\\.pb$", "");
            messages.add(new SessionMessage(
                    "system",
                    "该会话为 Antigravity CLI 早期版本（2026 年 6 月之前）生成的加密会话，"
                            + "本地文件（.pb）经过加密，无法解析预览。\n\n"
                            + "可在终端使用以下命令继续该会话查看内容：\nagy --conversation " + sessionId,
                    System.currentTimeMillis()));
            return messages;
        }

        // 新版会话（.db，SQLite）：逐步骤还原用户输入与模型回复
        List<SessionMessage> messages = new ArrayList<>();
        for (SessionMessage raw : AntigravityDbParser.loadMessages(file)) {
            String cleaned = cleanAntigravityText(raw.getContent());
            if (cleaned == null || cleaned.isBlank()) {
                continue;
            }
            messages.add(new SessionMessage(raw.getRole(), cleaned, raw.getTimestamp()));
        }

        if (messages.isEmpty()) {
            messages.add(new SessionMessage(
                    "system",
                    "未找到对话内容（可能由于会话正在进行中，或者数据库尚未写入完整）。",
                    System.currentTimeMillis()));
        }

        return messages;
    }

    private String formatToolCallsForDisplay(JsonArray toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (JsonElement tcElem : toolCalls) {
            if (!tcElem.isJsonObject()) continue;
            JsonObject tc = tcElem.getAsJsonObject();
            String name = getStr(tc, "name", "tool");
            if (!sb.isEmpty()) sb.append("\n");
            sb.append("🛠️ 正在执行工具: ").append(name);
        }
        return sb.toString();
    }
}
