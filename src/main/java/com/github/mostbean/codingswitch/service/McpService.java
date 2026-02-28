package com.github.mostbean.codingswitch.service;

import com.github.mostbean.codingswitch.model.CliType;
import com.github.mostbean.codingswitch.model.McpServer;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * MCP 服务器管理服务。
 */
@Service(Service.Level.APP)
@State(name = "CodingSwitchMcp", storages = @Storage("coding-switch-mcp.xml"))
public final class McpService implements PersistentStateComponent<McpService.StateData> {

    private static final Logger LOG = Logger.getInstance(McpService.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static class StateData {
        public String serversJson = "[]";
    }

    public static class ImportOptions {
        public boolean includeClaudeUserScope = true;
        public boolean includeClaudeProjectScope = true;
        public boolean includeCurrentProjectLocalScope = true;
    }

    public static class ImportReport {
        public int newlyImported;
        public int mergedExisting;
        public int skippedInvalid;
        public final List<String> warnings = new ArrayList<>();

        public int getTouchedCount() {
            return newlyImported + mergedExisting;
        }
    }

    private StateData myState = new StateData();
    private final List<Runnable> changeListeners = new ArrayList<>();

    public static McpService getInstance() {
        return ApplicationManager.getApplication().getService(McpService.class);
    }

    @Override
    public @Nullable StateData getState() {
        myState.serversJson = GSON.toJson(getServers());
        return myState;
    }

    @Override
    public void loadState(@NotNull StateData state) {
        myState = state;
    }

    public List<McpServer> getServers() {
        try {
            List<McpServer> list = GSON.fromJson(myState.serversJson, new TypeToken<List<McpServer>>() {
            }.getType());
            return list != null ? list : new ArrayList<>();
        } catch (Exception e) {
            LOG.warn("Failed to parse MCP servers", e);
            return new ArrayList<>();
        }
    }

    public void addServer(McpServer server) {
        List<McpServer> servers = new ArrayList<>(getServers());
        servers.add(server);
        saveServers(servers);
        trySyncAllSafe();
    }

    public void updateServer(McpServer server) {
        List<McpServer> servers = new ArrayList<>(getServers());
        servers.replaceAll(s -> s.getId().equals(server.getId()) ? server : s);
        saveServers(servers);
        trySyncAllSafe();
    }

    public void removeServer(String serverId) {
        List<McpServer> servers = new ArrayList<>(getServers());
        servers.removeIf(s -> s.getId().equals(serverId));
        saveServers(servers);
        trySyncAllSafe();
    }

    private void trySyncAllSafe() {
        try {
            syncToAllConfigs();
        } catch (IOException e) {
            LOG.warn("Failed to sync MCP configs after update", e);
        }
    }

    public void syncToConfig(CliType cliType) throws IOException {
        List<McpServer> enabledServers = getServers().stream()
                .filter(McpServer::isEnabled)
                .filter(s -> s.isSyncedTo(cliType))
                .toList();

        ConfigFileService configService = ConfigFileService.getInstance();
        switch (cliType) {
            case CLAUDE -> syncClaudeMcp(configService, enabledServers);
            case GEMINI -> syncGeminiMcp(configService, enabledServers);
            case OPENCODE -> syncOpenCodeMcp(configService, enabledServers);
            case CODEX -> syncCodexMcp(configService, enabledServers);
        }
    }

    public void syncToAllConfigs() throws IOException {
        for (CliType cli : CliType.values()) {
            syncToConfig(cli);
        }
    }

    private void syncClaudeMcp(ConfigFileService svc, List<McpServer> servers) throws IOException {
        Path path = svc.getMcpConfigPath(CliType.CLAUDE);
        JsonObject root = svc.readJsonFile(path);
        if (servers.isEmpty()) {
            root.remove("mcpServers");
        } else {
            root.add("mcpServers", buildMcpServersJson(servers));
        }
        svc.writeJsonFile(path, root);
    }

    private void syncGeminiMcp(ConfigFileService svc, List<McpServer> servers) throws IOException {
        Path path = svc.getMcpConfigPath(CliType.GEMINI);
        JsonObject root = svc.readJsonFile(path);
        if (servers.isEmpty()) {
            root.remove("mcpServers");
        } else {
            root.add("mcpServers", buildMcpServersJson(servers));
        }
        svc.writeJsonFile(path, root);
    }

    private void syncOpenCodeMcp(ConfigFileService svc, List<McpServer> servers) throws IOException {
        Path path = svc.getMcpConfigPath(CliType.OPENCODE);
        JsonObject root = svc.readJsonFile(path);
        if (servers.isEmpty()) {
            root.remove("mcp");
        } else {
            root.add("mcp", buildOpenCodeMcpJson(servers));
        }
        svc.writeJsonFile(path, root);
    }

    private void syncCodexMcp(ConfigFileService svc, List<McpServer> servers) throws IOException {
        Path path = svc.getMcpConfigPath(CliType.CODEX);
        List<String> managedNames = servers.stream()
                .map(McpServer::getName)
                .filter(name -> name != null && !name.isBlank())
                .toList();

        StringBuilder sb = new StringBuilder();
        sb.append("# >>> coding-switch:mcp:start\n");
        sb.append("# MCP Servers (managed by Coding Switch)\n\n");
        for (McpServer server : servers) {
            sb.append("[mcp_servers.").append(server.getName()).append("]\n");
            if (server.getTransportType() == McpServer.TransportType.STDIO) {
                sb.append("type = \"stdio\"\n");
                if (server.getCommand() != null && !server.getCommand().isBlank()) {
                    sb.append("command = \"").append(escapeToml(server.getCommand())).append("\"\n");
                }
            } else {
                sb.append("type = \"")
                        .append(server.getTransportType() == McpServer.TransportType.SSE ? "sse" : "http")
                        .append("\"\n");
                if (server.getUrl() != null && !server.getUrl().isBlank()) {
                    sb.append("url = \"").append(escapeToml(server.getUrl())).append("\"\n");
                }
            }
            if (server.getArgs() != null && server.getArgs().length > 0) {
                sb.append("args = [");
                for (int i = 0; i < server.getArgs().length; i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    sb.append("\"").append(escapeToml(server.getArgs()[i])).append("\"");
                }
                sb.append("]\n");
            }
            if (server.getEnv() != null && !server.getEnv().isEmpty()) {
                sb.append("[mcp_servers.").append(server.getName()).append(".env]\n");
                for (Map.Entry<String, String> entry : server.getEnv().entrySet()) {
                    sb.append(entry.getKey()).append(" = \"").append(escapeToml(entry.getValue())).append("\"\n");
                }
            }
            sb.append("\n");
        }
        sb.append("# <<< coding-switch:mcp:end\n");

        String existing = svc.readFile(path);
        String withoutManagedBlock = removeManagedBlock(existing,
                "# >>> coding-switch:mcp:start",
                "# <<< coding-switch:mcp:end");
        String sanitized = removeConflictingCodexSections(withoutManagedBlock, managedNames);
        String merged = upsertManagedBlock(sanitized, sb.toString(),
                "# >>> coding-switch:mcp:start",
                "# <<< coding-switch:mcp:end");
        svc.writeFile(path, merged);
    }

    private JsonObject buildMcpServersJson(List<McpServer> servers) {
        JsonObject mcpServers = new JsonObject();
        for (McpServer server : servers) {
            JsonObject serverJson = new JsonObject();
            switch (server.getTransportType()) {
                case STDIO -> {
                    serverJson.addProperty("command", server.getCommand());
                    if (server.getArgs() != null) {
                        serverJson.add("args", GSON.toJsonTree(server.getArgs()));
                    }
                }
                case SSE, HTTP -> serverJson.addProperty("url", server.getUrl());
            }
            if (server.getEnv() != null && !server.getEnv().isEmpty()) {
                serverJson.add("env", GSON.toJsonTree(server.getEnv()));
            }
            mcpServers.add(server.getName(), serverJson);
        }
        return mcpServers;
    }

    private JsonObject buildOpenCodeMcpJson(List<McpServer> servers) {
        JsonObject mcp = new JsonObject();
        for (McpServer server : servers) {
            JsonObject serverJson = new JsonObject();
            if (server.getTransportType() == McpServer.TransportType.STDIO) {
                serverJson.addProperty("type", "local");
                JsonArray command = new JsonArray();
                if (server.getCommand() != null && !server.getCommand().isBlank()) {
                    command.add(server.getCommand());
                }
                if (server.getArgs() != null) {
                    for (String arg : server.getArgs()) {
                        command.add(arg);
                    }
                }
                serverJson.add("command", command);
            } else {
                serverJson.addProperty("type", "remote");
                serverJson.addProperty("url", server.getUrl());
            }
            if (server.getEnv() != null && !server.getEnv().isEmpty()) {
                serverJson.add("environment", GSON.toJsonTree(server.getEnv()));
            }
            mcp.add(server.getName(), serverJson);
        }
        return mcp;
    }

    public int importFromCliConfigs() {
        return importFromCliConfigs(null, new ImportOptions()).newlyImported;
    }

    public ImportReport importFromCliConfigs(@Nullable Path currentProjectRoot, @Nullable ImportOptions options) {
        ImportOptions opts = options != null ? options : new ImportOptions();
        ConfigFileService configService = ConfigFileService.getInstance();
        List<McpServer> existing = new ArrayList<>(getServers());
        String beforeState = GSON.toJson(existing);
        ImportReport report = new ImportReport();

        importFromClaudeScopes(configService, existing, currentProjectRoot, opts, report);
        importFromJsonMcpServers(configService, existing, CliType.GEMINI, report);
        importFromOpenCodeConfig(configService, existing, report);
        importFromCodexToml(existing, report);

        boolean changed = !beforeState.equals(GSON.toJson(existing));
        if (changed) {
            saveServers(existing);
        }
        return report;
    }

    private void importFromClaudeScopes(ConfigFileService configService, List<McpServer> existing,
                                        @Nullable Path currentProjectRoot, ImportOptions options, ImportReport report) {
        try {
            Path userPath = configService.getMcpConfigPath(CliType.CLAUDE);
            JsonObject root = configService.readJsonFile(userPath);

            if (options.includeClaudeUserScope) {
                importFromJsonServerObject(
                        root.has("mcpServers") && root.get("mcpServers").isJsonObject()
                                ? root.getAsJsonObject("mcpServers")
                                : null,
                        existing, CliType.CLAUDE, "Claude(user)", report);
            }

            if (options.includeClaudeProjectScope && currentProjectRoot != null
                    && root.has("projects") && root.get("projects").isJsonObject()) {
                JsonObject projects = root.getAsJsonObject("projects");
                String matchKey = findMatchingProjectKey(projects, currentProjectRoot);
                if (matchKey != null) {
                    JsonElement elem = projects.get(matchKey);
                    if (elem != null && elem.isJsonObject()) {
                        JsonObject projectObj = elem.getAsJsonObject();
                        importFromJsonServerObject(
                                projectObj.has("mcpServers") && projectObj.get("mcpServers").isJsonObject()
                                        ? projectObj.getAsJsonObject("mcpServers")
                                        : null,
                                existing, CliType.CLAUDE, "Claude(project:" + matchKey + ")", report);
                    }
                }
            }

            if (options.includeCurrentProjectLocalScope && currentProjectRoot != null) {
                Path localPath = currentProjectRoot.resolve(".mcp.json");
                if (Files.exists(localPath)) {
                    JsonObject localRoot = configService.readJsonFile(localPath);
                    JsonObject localServers = null;
                    if (localRoot.has("mcpServers") && localRoot.get("mcpServers").isJsonObject()) {
                        localServers = localRoot.getAsJsonObject("mcpServers");
                    } else if (looksLikeServerMap(localRoot)) {
                        localServers = localRoot;
                    }
                    importFromJsonServerObject(localServers, existing, CliType.CLAUDE,
                            "Claude(local:" + localPath + ")", report);
                }
            }
        } catch (Exception e) {
            LOG.info("Failed to import MCP from Claude: " + e.getMessage());
            report.warnings.add("Claude 导入失败: " + e.getMessage());
        }
    }

    private void importFromJsonMcpServers(ConfigFileService configService, List<McpServer> existing,
                                          CliType cliType, ImportReport report) {
        try {
            Path path = configService.getMcpConfigPath(cliType);
            JsonObject root = configService.readJsonFile(path);
            if (!root.has("mcpServers")) {
                return;
            }
            JsonObject mcpServers = root.getAsJsonObject("mcpServers");
            importFromJsonServerObject(mcpServers, existing, cliType, cliType.getDisplayName(), report);
        } catch (Exception e) {
            LOG.info("Failed to import MCP from " + cliType.getDisplayName() + ": " + e.getMessage());
            report.warnings.add(cliType.getDisplayName() + " 导入失败: " + e.getMessage());
        }
    }

    private void importFromOpenCodeConfig(ConfigFileService configService, List<McpServer> existing, ImportReport report) {
        try {
            Path path = configService.getMcpConfigPath(CliType.OPENCODE);
            JsonObject root = configService.readJsonFile(path);
            JsonObject mcp = null;
            if (root.has("mcp") && root.get("mcp").isJsonObject()) {
                mcp = root.getAsJsonObject("mcp");
            } else if (root.has("mcpServers") && root.get("mcpServers").isJsonObject()) {
                mcp = root.getAsJsonObject("mcpServers");
            }
            if (mcp == null) {
                return;
            }

            for (String serverName : mcp.keySet()) {
                JsonElement element = mcp.get(serverName);
                if (element == null || !element.isJsonObject()) {
                    report.skippedInvalid++;
                    continue;
                }
                McpServer server = parseOpenCodeServer(serverName, element.getAsJsonObject());
                mergeImportedServer(existing, server, CliType.OPENCODE, "OpenCode", report);
            }
        } catch (Exception e) {
            LOG.info("Failed to import MCP from OpenCode: " + e.getMessage());
            report.warnings.add("OpenCode 导入失败: " + e.getMessage());
        }
    }

    private void importFromCodexToml(List<McpServer> existing, ImportReport report) {
        try {
            ConfigFileService configService = ConfigFileService.getInstance();
            Path path = configService.getMcpConfigPath(CliType.CODEX);
            String content = configService.readFile(path);
            if (content.isBlank()) {
                return;
            }

            String currentSection = null;
            String currentCommand = null;
            String currentUrl = null;
            List<String> currentArgs = new ArrayList<>();
            Map<String, String> currentEnv = new HashMap<>();
            String currentType = "stdio";
            boolean inEnvSection = false;

            for (String rawLine : content.split("\n")) {
                String line = rawLine.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }

                if (line.startsWith("[") && line.endsWith("]")) {
                    String sectionName = line.substring(1, line.length() - 1).trim();
                    if (sectionName.startsWith("mcp_servers.")) {
                        String suffix = sectionName.substring("mcp_servers.".length());
                        boolean envSection = suffix.endsWith(".env");
                        String nextSection = envSection ? suffix.substring(0, suffix.length() - 4) : suffix;

                        if (currentSection != null && !currentSection.equals(nextSection)) {
                            saveCodexSection(existing, currentSection, currentType, currentCommand,
                                    currentArgs, currentUrl, currentEnv, report);
                            currentCommand = null;
                            currentUrl = null;
                            currentArgs = new ArrayList<>();
                            currentEnv = new HashMap<>();
                            currentType = "stdio";
                        }

                        if (currentSection == null) {
                            currentCommand = null;
                            currentUrl = null;
                            currentArgs = new ArrayList<>();
                            currentEnv = new HashMap<>();
                            currentType = "stdio";
                        }
                        currentSection = nextSection;
                        inEnvSection = envSection;
                    } else {
                        if (currentSection != null) {
                            saveCodexSection(existing, currentSection, currentType, currentCommand,
                                    currentArgs, currentUrl, currentEnv, report);
                        }
                        currentSection = null;
                        inEnvSection = false;
                        currentCommand = null;
                        currentUrl = null;
                        currentArgs = new ArrayList<>();
                        currentEnv = new HashMap<>();
                        currentType = "stdio";
                    }
                    continue;
                }

                if (currentSection == null) {
                    continue;
                }

                int eq = line.indexOf('=');
                if (eq < 0) {
                    continue;
                }
                String key = line.substring(0, eq).trim();
                String value = line.substring(eq + 1).trim();

                if (inEnvSection) {
                    currentEnv.put(key, stripQuotes(value));
                    continue;
                }

                switch (key) {
                    case "type" -> currentType = stripQuotes(value);
                    case "command" -> currentCommand = stripQuotes(value);
                    case "url" -> currentUrl = stripQuotes(value);
                    case "args" -> currentArgs = parseTomlArray(value);
                }
            }

            if (currentSection != null) {
                saveCodexSection(existing, currentSection, currentType, currentCommand,
                        currentArgs, currentUrl, currentEnv, report);
            }
        } catch (Exception e) {
            LOG.info("Failed to import MCP from Codex: " + e.getMessage());
            report.warnings.add("Codex 导入失败: " + e.getMessage());
        }
    }

    private void saveCodexSection(List<McpServer> existing, String name,
                                  String type, String command, List<String> args, String url,
                                  Map<String, String> env, ImportReport report) {
        McpServer server = new McpServer();
        server.setName(name);
        server.setEnabled(true);
        server.setSyncedTo(CliType.CODEX, true);

        if ("stdio".equals(type) && command != null) {
            server.setTransportType(McpServer.TransportType.STDIO);
            server.setCommand(command);
            server.setArgs(args.toArray(new String[0]));
        } else if (("http".equals(type) || "sse".equals(type)) && url != null) {
            server.setTransportType("sse".equals(type) ? McpServer.TransportType.SSE : McpServer.TransportType.HTTP);
            server.setUrl(url);
        } else {
            report.skippedInvalid++;
            report.warnings.add("Codex 导入跳过无效 MCP: " + name);
            return;
        }

        if (!env.isEmpty()) {
            server.setEnv(env);
        }
        mergeImportedServer(existing, server, CliType.CODEX, "Codex", report);
    }

    private static String stripQuotes(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static List<String> parseTomlArray(String value) {
        List<String> result = new ArrayList<>();
        if (value.startsWith("[") && value.endsWith("]")) {
            String inner = value.substring(1, value.length() - 1);
            for (String item : inner.split(",")) {
                String trimmed = stripQuotes(item.trim());
                if (!trimmed.isEmpty()) {
                    result.add(trimmed);
                }
            }
        }
        return result;
    }

    private McpServer parseMcpServerFromJson(String name, JsonObject json, CliType source) {
        McpServer server = new McpServer();
        server.setName(name);
        server.setEnabled(true);
        server.setSyncedTo(source, true);

        if (json.has("command")) {
            server.setTransportType(McpServer.TransportType.STDIO);
            server.setCommand(json.get("command").getAsString());
            if (json.has("args") && json.get("args").isJsonArray()) {
                JsonArray argsArr = json.getAsJsonArray("args");
                String[] args = new String[argsArr.size()];
                for (int i = 0; i < argsArr.size(); i++) {
                    args[i] = argsArr.get(i).getAsString();
                }
                server.setArgs(args);
            } else {
                server.setArgs(new String[0]);
            }
        } else if (json.has("url")) {
            String url = json.get("url").getAsString();
            server.setTransportType(url.contains("/sse") ? McpServer.TransportType.SSE : McpServer.TransportType.HTTP);
            server.setUrl(url);
        } else {
            return null;
        }

        if (json.has("env") && json.get("env").isJsonObject()) {
            Map<String, String> env = new HashMap<>();
            JsonObject envJson = json.getAsJsonObject("env");
            for (String key : envJson.keySet()) {
                env.put(key, envJson.get(key).getAsString());
            }
            server.setEnv(env);
        }

        return server;
    }

    private McpServer parseOpenCodeServer(String name, JsonObject json) {
        McpServer server = new McpServer();
        server.setName(name);
        server.setEnabled(true);
        server.setSyncedTo(CliType.OPENCODE, true);

        String type = json.has("type") ? json.get("type").getAsString() : "";
        if ("local".equals(type) && json.has("command") && json.get("command").isJsonArray()) {
            JsonArray commandArray = json.getAsJsonArray("command");
            if (commandArray.size() == 0) {
                return null;
            }
            server.setTransportType(McpServer.TransportType.STDIO);
            server.setCommand(commandArray.get(0).getAsString());
            if (commandArray.size() > 1) {
                String[] args = new String[commandArray.size() - 1];
                for (int i = 1; i < commandArray.size(); i++) {
                    args[i - 1] = commandArray.get(i).getAsString();
                }
                server.setArgs(args);
            } else {
                server.setArgs(new String[0]);
            }
        } else if ("remote".equals(type) && json.has("url")) {
            String url = json.get("url").getAsString();
            server.setTransportType(url.contains("/sse") ? McpServer.TransportType.SSE : McpServer.TransportType.HTTP);
            server.setUrl(url);
        } else {
            return parseMcpServerFromJson(name, json, CliType.OPENCODE);
        }

        JsonObject envJson = null;
        if (json.has("environment") && json.get("environment").isJsonObject()) {
            envJson = json.getAsJsonObject("environment");
        } else if (json.has("env") && json.get("env").isJsonObject()) {
            envJson = json.getAsJsonObject("env");
        }
        if (envJson != null) {
            Map<String, String> env = new HashMap<>();
            for (String key : envJson.keySet()) {
                JsonElement value = envJson.get(key);
                if (!value.isJsonNull()) {
                    env.put(key, value.getAsString());
                }
            }
            if (!env.isEmpty()) {
                server.setEnv(env);
            }
        }

        return server;
    }

    private void importFromJsonServerObject(@Nullable JsonObject serverObject, List<McpServer> existing,
                                            CliType source, String sourceLabel, ImportReport report) {
        if (serverObject == null) {
            return;
        }
        for (String serverName : serverObject.keySet()) {
            JsonElement element = serverObject.get(serverName);
            if (element == null || !element.isJsonObject()) {
                report.skippedInvalid++;
                continue;
            }
            McpServer parsed = parseMcpServerFromJson(serverName, element.getAsJsonObject(), source);
            mergeImportedServer(existing, parsed, source, sourceLabel, report);
        }
    }

    private void mergeImportedServer(List<McpServer> existing, @Nullable McpServer imported,
                                     CliType source, String sourceLabel, ImportReport report) {
        if (imported == null || imported.getName() == null || imported.getName().isBlank()) {
            report.skippedInvalid++;
            return;
        }

        McpServer existingServer = existing.stream()
                .filter(s -> imported.getName().equals(s.getName()))
                .findFirst()
                .orElse(null);

        if (existingServer == null) {
            existing.add(imported);
            report.newlyImported++;
            return;
        }

        if (sameServerSignature(existingServer, imported)) {
            if (!existingServer.isSyncedTo(source)) {
                existingServer.setSyncedTo(source, true);
                report.mergedExisting++;
            }
            return;
        }

        report.skippedInvalid++;
        report.warnings.add("同名 MCP 冲突已跳过: " + imported.getName() + " (来源: " + sourceLabel + ")");
    }

    private static boolean sameServerSignature(McpServer a, McpServer b) {
        if (a.getTransportType() != b.getTransportType()) {
            return false;
        }
        return switch (a.getTransportType()) {
            case STDIO -> Objects.equals(a.getCommand(), b.getCommand())
                    && Arrays.equals(normalizeArgs(a.getArgs()), normalizeArgs(b.getArgs()))
                    && Objects.equals(normalizeEnv(a.getEnv()), normalizeEnv(b.getEnv()));
            case SSE, HTTP -> Objects.equals(a.getUrl(), b.getUrl())
                    && Objects.equals(normalizeEnv(a.getEnv()), normalizeEnv(b.getEnv()));
        };
    }

    private static String[] normalizeArgs(String[] args) {
        return args == null ? new String[0] : args;
    }

    private static Map<String, String> normalizeEnv(Map<String, String> env) {
        return env == null ? Collections.emptyMap() : env;
    }

    private static boolean looksLikeServerMap(JsonObject root) {
        for (String key : root.keySet()) {
            JsonElement element = root.get(key);
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            JsonObject item = element.getAsJsonObject();
            if (item.has("command") || item.has("url")) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    private static String findMatchingProjectKey(JsonObject projects, Path currentProjectRoot) {
        String normalizedCurrent = normalizePathString(currentProjectRoot);
        for (String rawKey : projects.keySet()) {
            try {
                String normalizedKey = normalizePathString(Path.of(rawKey));
                if (normalizedCurrent.equalsIgnoreCase(normalizedKey)) {
                    return rawKey;
                }
            } catch (Exception ignored) {
                // ignore malformed key
            }
        }
        return null;
    }

    private static String normalizePathString(Path path) {
        try {
            return path.toAbsolutePath().normalize().toString();
        } catch (Exception ignored) {
            return path.toString();
        }
    }

    private static String upsertManagedBlock(String existing, String block, String startMarker, String endMarker) {
        String safeExisting = existing == null ? "" : existing;
        int start = safeExisting.indexOf(startMarker);
        int end = safeExisting.indexOf(endMarker);
        if (start >= 0 && end >= start) {
            int endExclusive = end + endMarker.length();
            if (endExclusive < safeExisting.length() && safeExisting.charAt(endExclusive) == '\n') {
                endExclusive++;
            }
            return safeExisting.substring(0, start) + block + safeExisting.substring(endExclusive);
        }
        if (safeExisting.isBlank()) {
            return block;
        }
        return safeExisting + (safeExisting.endsWith("\n") ? "\n" : "\n\n") + block;
    }

    private static String removeManagedBlock(String existing, String startMarker, String endMarker) {
        String safeExisting = existing == null ? "" : existing;
        int start = safeExisting.indexOf(startMarker);
        int end = safeExisting.indexOf(endMarker);
        if (start < 0 || end < start) {
            return safeExisting;
        }
        int endExclusive = end + endMarker.length();
        if (endExclusive < safeExisting.length() && safeExisting.charAt(endExclusive) == '\n') {
            endExclusive++;
        }
        return safeExisting.substring(0, start) + safeExisting.substring(endExclusive);
    }

    private static String removeConflictingCodexSections(String content, List<String> managedNames) {
        if (content == null || content.isBlank() || managedNames == null || managedNames.isEmpty()) {
            return content == null ? "" : content;
        }

        StringBuilder out = new StringBuilder();
        String currentSection = null;
        boolean dropCurrentSection = false;

        for (String rawLine : content.split("\n", -1)) {
            String line = rawLine.trim();
            if (line.startsWith("[") && line.endsWith("]")) {
                currentSection = line.substring(1, line.length() - 1).trim();
                dropCurrentSection = isManagedCodexSection(currentSection, managedNames);
            }
            if (!dropCurrentSection) {
                out.append(rawLine).append("\n");
            }
        }
        return out.toString();
    }

    private static boolean isManagedCodexSection(String section, List<String> managedNames) {
        for (String name : managedNames) {
            String base = "mcp_servers." + name;
            String singleQuoted = "mcp_servers.'" + name + "'";
            String doubleQuoted = "mcp_servers.\"" + name + "\"";
            if (section.equals(base) || section.equals(base + ".env")
                    || section.equals(singleQuoted) || section.equals(singleQuoted + ".env")
                    || section.equals(doubleQuoted) || section.equals(doubleQuoted + ".env")) {
                return true;
            }
        }
        return false;
    }

    private static String escapeToml(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private void saveServers(List<McpServer> servers) {
        myState.serversJson = GSON.toJson(servers);
        fireChanged();
    }

    public void addChangeListener(Runnable listener) {
        changeListeners.add(listener);
    }

    private void fireChanged() {
        for (Runnable listener : changeListeners) {
            listener.run();
        }
    }
}
