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

    public enum AuthMode {
        API_KEY,
        OFFICIAL_LOGIN
    }

    private String id;
    private CliType cliType;
    private String name;
    private JsonObject settingsConfig;
    private AuthMode authMode;
    private boolean active;
    private boolean pendingActivation;
    private Long createdAt;

    public Provider() {
        this.id = UUID.randomUUID().toString();
        this.settingsConfig = new JsonObject();
        this.authMode = AuthMode.API_KEY;
        this.createdAt = System.currentTimeMillis();
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

    public AuthMode getAuthMode() {
        return authMode != null ? authMode : inferAuthMode(cliType, settingsConfig);
    }

    public AuthMode getStoredAuthMode() {
        return authMode;
    }

    public void setAuthMode(AuthMode authMode) {
        this.authMode = authMode;
    }

    public boolean usesOfficialLogin() {
        return getAuthMode() == AuthMode.OFFICIAL_LOGIN;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public boolean isPendingActivation() {
        return pendingActivation;
    }

    public void setPendingActivation(boolean pendingActivation) {
        this.pendingActivation = pendingActivation;
    }

    public Long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }

    /**
     * 创建当前 Provider 的深拷贝。
     */
    public Provider copy() {
        Provider copy = new Provider();
        copy.cliType = this.cliType;
        copy.name = this.name + " (Copy)";
        copy.settingsConfig = this.settingsConfig.deepCopy();
        copy.authMode = this.getAuthMode();
        copy.active = false;
        copy.pendingActivation = false;
        return copy;
    }

    public static AuthMode inferAuthMode(CliType cliType, JsonObject settingsConfig) {
        if (cliType == null) {
            return AuthMode.API_KEY;
        }

        JsonObject safeConfig = settingsConfig != null ? settingsConfig : new JsonObject();

        return switch (cliType) {
            case CLAUDE, GEMINI -> {
                JsonObject env = safeConfig.has("env") && safeConfig.get("env").isJsonObject()
                        ? safeConfig.getAsJsonObject("env")
                        : null;
                yield env == null || env.keySet().isEmpty() ? AuthMode.OFFICIAL_LOGIN : AuthMode.API_KEY;
            }
            case CODEX -> {
                JsonObject auth = safeConfig.has("auth") && safeConfig.get("auth").isJsonObject()
                        ? safeConfig.getAsJsonObject("auth")
                        : null;
                String config = safeConfig.has("config") && !safeConfig.get("config").isJsonNull()
                        ? safeConfig.get("config").getAsString()
                        : "";
                boolean authEmpty = auth == null || auth.keySet().isEmpty();
                boolean officialManagedConfig = config == null || config.isBlank()
                        || (!config.contains("model_provider =")
                                && !config.contains("[model_providers.")
                                && !config.contains("base_url =")
                                && !config.contains("requires_openai_auth ="));
                yield authEmpty && officialManagedConfig ? AuthMode.OFFICIAL_LOGIN : AuthMode.API_KEY;
            }
            case OPENCODE -> AuthMode.API_KEY;
        };
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
