package com.github.mostbean.codingswitch.model;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * MCP (Model Context Protocol) 服务器配置数据模型。
 */
public class McpServer {

    /** 传输类型 */
    public enum TransportType {
        STDIO, SSE, HTTP
    }

    private String id;
    private String name;
    private TransportType transportType;
    private String command;
    private String[] args;
    private String url;
    private Map<String, String> env;
    private boolean enabled;

    /** 该 MCP 服务器同步到哪些 CLI */
    private Map<CliType, Boolean> syncTargets;

    public McpServer() {
        this.id = UUID.randomUUID().toString();
        this.transportType = TransportType.STDIO;
        this.env = new HashMap<>();
        this.enabled = true;
        this.syncTargets = new HashMap<>();
        for (CliType cli : CliType.values()) {
            syncTargets.put(cli, false);
        }
    }

    // --- Getters & Setters ---

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public TransportType getTransportType() {
        return transportType;
    }

    public void setTransportType(TransportType transportType) {
        this.transportType = transportType;
    }

    public String getCommand() {
        return command;
    }

    public void setCommand(String command) {
        this.command = command;
    }

    public String[] getArgs() {
        return args;
    }

    public void setArgs(String[] args) {
        this.args = args;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public Map<String, String> getEnv() {
        return env;
    }

    public void setEnv(Map<String, String> env) {
        this.env = env;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Map<CliType, Boolean> getSyncTargets() {
        return syncTargets;
    }

    public void setSyncTargets(Map<CliType, Boolean> syncTargets) {
        this.syncTargets = syncTargets;
    }

    public boolean isSyncedTo(CliType cliType) {
        return Boolean.TRUE.equals(syncTargets.get(cliType));
    }

    public void setSyncedTo(CliType cliType, boolean synced) {
        syncTargets.put(cliType, synced);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        McpServer that = (McpServer) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return name + " (" + transportType + ")";
    }
}
