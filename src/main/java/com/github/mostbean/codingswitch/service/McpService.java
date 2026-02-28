package com.github.mostbean.codingswitch.service;

import com.github.mostbean.codingswitch.model.CliType;
import com.github.mostbean.codingswitch.model.McpServer;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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
import java.util.ArrayList;
import java.util.List;

/**
 * MCP 服务器管理服务。
 * 管理 MCP 服务器列表，并将启用的服务器同步写入各 CLI 的配置文件。
 */
@Service(Service.Level.APP)
@State(name = "CodingSwitchMcp", storages = @Storage("coding-switch-mcp.xml"))
public final class McpService implements PersistentStateComponent<McpService.State> {

    private static final Logger LOG = Logger.getInstance(McpService.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static class State {
        public String serversJson = "[]";
    }

    private State myState = new State();
    private final List<Runnable> changeListeners = new ArrayList<>();

    public static McpService getInstance() {
        return ApplicationManager.getApplication().getService(McpService.class);
    }

    @Override
    public @Nullable State getState() {
        myState.serversJson = GSON.toJson(getServers());
        return myState;
    }

    @Override
    public void loadState(@NotNull State state) {
        myState = state;
    }

    // =====================================================================
    // CRUD
    // =====================================================================

    public List<McpServer> getServers() {
        try {
            List<McpServer> list = GSON.fromJson(myState.serversJson,
                    new TypeToken<List<McpServer>>() {
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

    // =====================================================================
    // 同步到 CLI 配置文件
    // =====================================================================

    /**
     * 将所有启用的 MCP 服务器同步写入指定 CLI 的配置文件。
     */
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

    /** 同步到所有 CLI */
    public void syncToAllConfigs() throws IOException {
        for (CliType cli : CliType.values()) {
            syncToConfig(cli);
        }
    }

    private void syncClaudeMcp(ConfigFileService svc, List<McpServer> servers) throws IOException {
        var path = svc.getMcpConfigPath(CliType.CLAUDE);
        JsonObject root = svc.readJsonFile(path);
        if (servers.isEmpty()) {
            root.remove("mcpServers");
        } else {
            root.add("mcpServers", buildMcpServersJson(servers));
        }
        svc.writeJsonFile(path, root);
    }

    private void syncGeminiMcp(ConfigFileService svc, List<McpServer> servers) throws IOException {
        var path = svc.getMcpConfigPath(CliType.GEMINI);
        JsonObject root = svc.readJsonFile(path);
        if (servers.isEmpty()) {
            root.remove("mcpServers");
        } else {
            root.add("mcpServers", buildMcpServersJson(servers));
        }
        svc.writeJsonFile(path, root);
    }

    private void syncOpenCodeMcp(ConfigFileService svc, List<McpServer> servers) throws IOException {
        var path = svc.getMcpConfigPath(CliType.OPENCODE);
        JsonObject root = svc.readJsonFile(path);
        if (servers.isEmpty()) {
            root.remove("mcpServers");
        } else {
            root.add("mcpServers", buildMcpServersJson(servers));
        }
        svc.writeJsonFile(path, root);
    }

    private void syncCodexMcp(ConfigFileService svc, List<McpServer> servers) throws IOException {
        // Codex 使用 TOML 格式，这里简化为写 JSON 注释标记
        // 完整 TOML 支持在后续迭代中实现
        var path = svc.getMcpConfigPath(CliType.CODEX);
        StringBuilder sb = new StringBuilder();
        sb.append("# MCP Servers (managed by Coding Switch)\n\n");
        for (McpServer server : servers) {
            sb.append("[mcp_servers.").append(server.getName()).append("]\n");
            if (server.getCommand() != null) {
                sb.append("command = \"").append(server.getCommand()).append("\"\n");
            }
            if (server.getArgs() != null && server.getArgs().length > 0) {
                sb.append("args = [");
                for (int i = 0; i < server.getArgs().length; i++) {
                    if (i > 0)
                        sb.append(", ");
                    sb.append("\"").append(server.getArgs()[i]).append("\"");
                }
                sb.append("]\n");
            }
            sb.append("\n");
        }
        svc.writeFile(path, sb.toString());
    }

    /**
     * 构建 mcpServers JSON 对象（Claude/Gemini/OpenCode 通用格式）。
     */
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

    // =====================================================================
    // 从 CLI 配置文件导入已有 MCP 服务器
    // =====================================================================

    /**
     * 扫描所有 CLI 的配置文件，导入已有的 MCP 服务器。
     * <p>
     * 去重策略：已存在的同名 MCP 仅合并来源 CLI 开关，不覆盖其他字段。
     *
     * @return 新导入的服务器数量
     */
    public int importFromCliConfigs() {
        ConfigFileService configService = ConfigFileService.getInstance();
        List<McpServer> existing = new ArrayList<>(getServers());
        int importedCount = 0;
        boolean changed = false;

        // JSON 格式 CLI：Claude / Gemini / OpenCode
        for (CliType cli : new CliType[] { CliType.CLAUDE, CliType.GEMINI, CliType.OPENCODE }) {
            try {
                var path = configService.getMcpConfigPath(cli);
                JsonObject root = configService.readJsonFile(path);
                if (!root.has("mcpServers"))
                    continue;

                JsonObject mcpServers = root.getAsJsonObject("mcpServers");
                for (String serverName : mcpServers.keySet()) {
                    // 去重：按名称判断——已存在则仅合并 CLI 开关
                    McpServer existingServer = existing.stream()
                            .filter(s -> s.getName().equals(serverName)).findFirst().orElse(null);
                    if (existingServer != null) {
                        if (!existingServer.isSyncedTo(cli)) {
                            existingServer.setSyncedTo(cli, true);
                            changed = true;
                        }
                        continue;
                    }

                    JsonObject serverJson = mcpServers.getAsJsonObject(serverName);
                    McpServer server = parseMcpServerFromJson(serverName, serverJson, cli);
                    if (server != null) {
                        existing.add(server);
                        importedCount++;
                    }
                }
            } catch (Exception e) {
                LOG.info("Failed to import MCP from " + cli.getDisplayName() + ": " + e.getMessage());
            }
        }

        // TOML 格式 CLI：Codex
        try {
            int codexCount = importFromCodexToml(existing);
            importedCount += codexCount;
            if (codexCount > 0)
                changed = true;
        } catch (Exception e) {
            LOG.info("Failed to import MCP from Codex: " + e.getMessage());
        }

        if (importedCount > 0 || changed) {
            saveServers(existing);
        }
        return importedCount;
    }

    /**
     * 从 Codex 的 TOML 配置文件导入 MCP 服务器。
     * 解析 ~/.codex/config.toml 中 [mcp_servers.xxx] 段。
     */
    private int importFromCodexToml(List<McpServer> existing) {
        ConfigFileService configService = ConfigFileService.getInstance();
        var path = configService.getMcpConfigPath(CliType.CODEX);
        String content = configService.readFile(path);
        if (content.isBlank())
            return 0;

        int importedCount = 0;
        String currentSection = null;
        String currentCommand = null;
        String currentUrl = null;
        List<String> currentArgs = new ArrayList<>();
        java.util.Map<String, String> currentEnv = new java.util.HashMap<>();
        String currentType = "stdio";

        for (String rawLine : content.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#"))
                continue;

            // 检测段头：[mcp_servers.xxx]
            if (line.startsWith("[") && line.endsWith("]")) {
                // 先保存上一个段
                if (currentSection != null) {
                    importedCount += saveCodexSection(existing, currentSection,
                            currentType, currentCommand, currentArgs, currentUrl, currentEnv);
                }
                String sectionName = line.substring(1, line.length() - 1).trim();
                if (sectionName.startsWith("mcp_servers.")) {
                    currentSection = sectionName.substring("mcp_servers.".length());
                } else {
                    currentSection = null; // 非 MCP 段，跳过
                }
                currentCommand = null;
                currentUrl = null;
                currentArgs = new ArrayList<>();
                currentEnv = new java.util.HashMap<>();
                currentType = "stdio";
                continue;
            }

            if (currentSection == null)
                continue;

            // 解析 key = value
            int eq = line.indexOf('=');
            if (eq < 0)
                continue;
            String key = line.substring(0, eq).trim();
            String value = line.substring(eq + 1).trim();

            switch (key) {
                case "type" -> currentType = stripQuotes(value);
                case "command" -> currentCommand = stripQuotes(value);
                case "url" -> currentUrl = stripQuotes(value);
                case "args" -> currentArgs = parseTomlArray(value);
            }
        }

        // 保存最后一个段
        if (currentSection != null) {
            importedCount += saveCodexSection(existing, currentSection,
                    currentType, currentCommand, currentArgs, currentUrl, currentEnv);
        }

        return importedCount;
    }

    private int saveCodexSection(List<McpServer> existing, String name,
            String type, String command, List<String> args, String url,
            java.util.Map<String, String> env) {
        // 去重：已存在则仅合并 Codex CLI 开关
        McpServer existingServer = existing.stream()
                .filter(s -> s.getName().equals(name)).findFirst().orElse(null);
        if (existingServer != null) {
            if (!existingServer.isSyncedTo(CliType.CODEX)) {
                existingServer.setSyncedTo(CliType.CODEX, true);
            }
            return 0;
        }

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
            return 0; // 无法识别
        }

        if (!env.isEmpty())
            server.setEnv(env);
        existing.add(server);
        return 1;
    }

    private static String stripQuotes(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static List<String> parseTomlArray(String value) {
        List<String> result = new ArrayList<>();
        // 简单解析 ["a", "b", "c"] 格式
        if (value.startsWith("[") && value.endsWith("]")) {
            String inner = value.substring(1, value.length() - 1);
            for (String item : inner.split(",")) {
                String trimmed = stripQuotes(item.trim());
                if (!trimmed.isEmpty())
                    result.add(trimmed);
            }
        }
        return result;
    }

    /**
     * 从 JSON 节点解析出 McpServer 对象。
     */
    private McpServer parseMcpServerFromJson(String name, JsonObject json, CliType source) {
        McpServer server = new McpServer();
        server.setName(name);
        server.setEnabled(true);
        // 只启用来源 CLI，而非全部
        server.setSyncedTo(source, true);

        if (json.has("command")) {
            // STDIO 模式
            server.setTransportType(McpServer.TransportType.STDIO);
            server.setCommand(json.get("command").getAsString());
            if (json.has("args") && json.get("args").isJsonArray()) {
                var argsArr = json.getAsJsonArray("args");
                String[] args = new String[argsArr.size()];
                for (int i = 0; i < argsArr.size(); i++) {
                    args[i] = argsArr.get(i).getAsString();
                }
                server.setArgs(args);
            }
        } else if (json.has("url")) {
            // SSE/HTTP 模式
            String url = json.get("url").getAsString();
            server.setTransportType(url.contains("/sse") ? McpServer.TransportType.SSE : McpServer.TransportType.HTTP);
            server.setUrl(url);
        } else {
            return null; // 无法识别的格式
        }

        // 导入环境变量
        if (json.has("env") && json.get("env").isJsonObject()) {
            java.util.Map<String, String> env = new java.util.HashMap<>();
            JsonObject envJson = json.getAsJsonObject("env");
            for (String key : envJson.keySet()) {
                env.put(key, envJson.get(key).getAsString());
            }
            server.setEnv(env);
        }

        return server;
    }

    // =====================================================================
    // 内部工具
    // =====================================================================

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
