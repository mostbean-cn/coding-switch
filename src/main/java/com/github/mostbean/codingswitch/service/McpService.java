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
    }

    public void updateServer(McpServer server) {
        List<McpServer> servers = new ArrayList<>(getServers());
        servers.replaceAll(s -> s.getId().equals(server.getId()) ? server : s);
        saveServers(servers);
    }

    public void removeServer(String serverId) {
        List<McpServer> servers = new ArrayList<>(getServers());
        servers.removeIf(s -> s.getId().equals(serverId));
        saveServers(servers);
    }

    public void toggleServer(String serverId) {
        List<McpServer> servers = new ArrayList<>(getServers());
        for (McpServer s : servers) {
            if (s.getId().equals(serverId)) {
                s.setEnabled(!s.isEnabled());
                break;
            }
        }
        saveServers(servers);
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
        root.add("mcpServers", buildMcpServersJson(servers));
        svc.writeJsonFile(path, root);
    }

    private void syncGeminiMcp(ConfigFileService svc, List<McpServer> servers) throws IOException {
        var path = svc.getMcpConfigPath(CliType.GEMINI);
        JsonObject root = svc.readJsonFile(path);
        root.add("mcpServers", buildMcpServersJson(servers));
        svc.writeJsonFile(path, root);
    }

    private void syncOpenCodeMcp(ConfigFileService svc, List<McpServer> servers) throws IOException {
        var path = svc.getMcpConfigPath(CliType.OPENCODE);
        JsonObject root = svc.readJsonFile(path);
        root.add("mcpServers", buildMcpServersJson(servers));
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
     * 扫描所有 CLI 的配置文件，导入已有的 MCP 服务器（去重）。
     *
     * @return 新导入的服务器数量
     */
    public int importFromCliConfigs() {
        ConfigFileService configService = ConfigFileService.getInstance();
        List<McpServer> existing = new ArrayList<>(getServers());
        int importedCount = 0;

        for (CliType cli : new CliType[] { CliType.CLAUDE, CliType.GEMINI, CliType.OPENCODE }) {
            try {
                var path = configService.getMcpConfigPath(cli);
                JsonObject root = configService.readJsonFile(path);
                if (!root.has("mcpServers"))
                    continue;

                JsonObject mcpServers = root.getAsJsonObject("mcpServers");
                for (String serverName : mcpServers.keySet()) {
                    // 去重：按名称判断
                    if (existing.stream().anyMatch(s -> s.getName().equals(serverName)))
                        continue;

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

        if (importedCount > 0) {
            saveServers(existing);
        }
        return importedCount;
    }

    /**
     * 从 JSON 节点解析出 McpServer 对象。
     */
    private McpServer parseMcpServerFromJson(String name, JsonObject json, CliType source) {
        McpServer server = new McpServer();
        server.setName(name);
        server.setEnabled(true);
        // 默认同步到所有 CLI
        for (CliType cli : CliType.values()) {
            server.setSyncedTo(cli, true);
        }

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
