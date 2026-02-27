package com.github.mostbean.codingswitch.model;

import com.google.gson.JsonObject;

import java.util.Objects;
import java.util.UUID;

/**
 * API Provider 配置数据模型。
 * <p>
 * settingsConfig 是动态 JSON，格式因 CLI 类型而异：
 * <ul>
 * <li>Claude:
 * {@code { "env": { "ANTHROPIC_BASE_URL": "...", "ANTHROPIC_AUTH_TOKEN": "...", ... } }}</li>
 * <li>Codex:
 * {@code { "auth": { "OPENAI_API_KEY": "..." }, "config": "toml content..." }}</li>
 * <li>Gemini:
 * {@code { "env": { "GEMINI_API_KEY": "...", "GOOGLE_GEMINI_BASE_URL": "...", ... } }}</li>
 * <li>OpenCode:
 * {@code { "npm": "...", "options": { "baseURL": "...", "apiKey": "..." }, "models": {...} }}</li>
 * </ul>
 */
public class Provider {

    private String id;
    private CliType cliType;
    private String name;
    private JsonObject settingsConfig;
    private boolean active;

    public Provider() {
        this.id = UUID.randomUUID().toString();
        this.settingsConfig = new JsonObject();
    }

    public Provider(CliType cliType, String name) {
        this();
        this.cliType = cliType;
        this.name = name;
    }

    // --- Getters & Setters ---

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public CliType getCliType() {
        return cliType;
    }

    public void setCliType(CliType cliType) {
        this.cliType = cliType;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public JsonObject getSettingsConfig() {
        return settingsConfig;
    }

    public void setSettingsConfig(JsonObject settingsConfig) {
        this.settingsConfig = settingsConfig;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    /**
     * 创建当前 Provider 的深拷贝。
     */
    public Provider copy() {
        Provider copy = new Provider();
        copy.cliType = this.cliType;
        copy.name = this.name + " (Copy)";
        copy.settingsConfig = this.settingsConfig.deepCopy();
        copy.active = false;
        return copy;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        Provider provider = (Provider) o;
        return Objects.equals(id, provider.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return name + " (" + cliType.getDisplayName() + ")";
    }
}
