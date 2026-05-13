package com.github.mostbean.codingswitch.model;

import java.util.Objects;
import java.util.UUID;

/**
 * 插件 AI 功能专用模型配置，与 CLI Provider 配置完全隔离。
 */
public class AiModelProfile {

    private String id;
    private String name;
    private AiModelFormat format;
    private String baseUrl;
    private String model;
    private String apiKey;
    private int timeoutSeconds;
    private String headersJson;
    private boolean fimEnabled;
    private String fimPrefixToken;
    private String fimSuffixToken;
    private String fimMiddleToken;

    public AiModelProfile() {
        this.id = UUID.randomUUID().toString();
        this.name = "";
        this.format = AiModelFormat.FIM_COMPLETIONS;
        this.baseUrl = this.format.getDefaultBaseUrl();
        this.model = "";
        this.apiKey = "";
        this.timeoutSeconds = 30;
        this.headersJson = "";
        this.fimEnabled = false;
        this.fimPrefixToken = "<|fim_prefix|>";
        this.fimSuffixToken = "<|fim_suffix|>";
        this.fimMiddleToken = "<|fim_middle|>";
    }

    public AiModelProfile copy() {
        AiModelProfile copy = new AiModelProfile();
        copy.id = this.id;
        copy.name = this.name;
        copy.format = this.format;
        copy.baseUrl = this.baseUrl;
        copy.model = this.model;
        copy.apiKey = this.apiKey;
        copy.timeoutSeconds = this.timeoutSeconds;
        copy.headersJson = this.headersJson;
        copy.fimEnabled = this.fimEnabled;
        copy.fimPrefixToken = this.fimPrefixToken;
        copy.fimSuffixToken = this.fimSuffixToken;
        copy.fimMiddleToken = this.fimMiddleToken;
        return copy;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name == null ? "" : name;
    }

    public void setName(String name) {
        this.name = name == null ? "" : name;
    }

    public AiModelFormat getFormat() {
        return format == null ? AiModelFormat.OPENAI_RESPONSES : format;
    }

    public void setFormat(AiModelFormat format) {
        this.format = format == null ? AiModelFormat.OPENAI_RESPONSES : format;
    }

    public String getBaseUrl() {
        String value = baseUrl == null ? "" : baseUrl.trim();
        return value.isBlank() ? getFormat().getDefaultBaseUrl() : value;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
    }

    public String getModel() {
        return model == null ? "" : model.trim();
    }

    public void setModel(String model) {
        this.model = model == null ? "" : model.trim();
    }

    public String getApiKey() {
        return apiKey == null ? "" : apiKey.trim();
    }

    public boolean hasApiKeySetting() {
        return apiKey != null;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds <= 0 ? 30 : timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = Math.max(1, timeoutSeconds);
    }

    public String getHeadersJson() {
        return headersJson == null ? "" : headersJson.trim();
    }

    public void setHeadersJson(String headersJson) {
        this.headersJson = headersJson == null ? "" : headersJson.trim();
    }

    public boolean isFimEnabled() {
        return fimEnabled;
    }

    public void setFimEnabled(boolean fimEnabled) {
        this.fimEnabled = fimEnabled;
    }

    public String getFimPrefixToken() {
        return fimPrefixToken == null || fimPrefixToken.isBlank() ? "<|fim_prefix|>" : fimPrefixToken;
    }

    public void setFimPrefixToken(String fimPrefixToken) {
        this.fimPrefixToken = fimPrefixToken == null || fimPrefixToken.isBlank() ? "<|fim_prefix|>" : fimPrefixToken;
    }

    public String getFimSuffixToken() {
        return fimSuffixToken == null || fimSuffixToken.isBlank() ? "<|fim_suffix|>" : fimSuffixToken;
    }

    public void setFimSuffixToken(String fimSuffixToken) {
        this.fimSuffixToken = fimSuffixToken == null || fimSuffixToken.isBlank() ? "<|fim_suffix|>" : fimSuffixToken;
    }

    public String getFimMiddleToken() {
        return fimMiddleToken == null || fimMiddleToken.isBlank() ? "<|fim_middle|>" : fimMiddleToken;
    }

    public void setFimMiddleToken(String fimMiddleToken) {
        this.fimMiddleToken = fimMiddleToken == null || fimMiddleToken.isBlank() ? "<|fim_middle|>" : fimMiddleToken;
    }

    public String getDisplayName() {
        String profileName = getName();
        return profileName.isBlank() ? getFormat().getDisplayName() : profileName;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AiModelProfile that)) {
            return false;
        }
        return getTimeoutSeconds() == that.getTimeoutSeconds()
            && fimEnabled == that.fimEnabled
            && Objects.equals(getId(), that.getId())
            && Objects.equals(getName(), that.getName())
            && getFormat() == that.getFormat()
            && Objects.equals(getBaseUrl(), that.getBaseUrl())
            && Objects.equals(getModel(), that.getModel())
            && Objects.equals(getApiKey(), that.getApiKey())
            && Objects.equals(getHeadersJson(), that.getHeadersJson())
            && Objects.equals(getFimPrefixToken(), that.getFimPrefixToken())
            && Objects.equals(getFimSuffixToken(), that.getFimSuffixToken())
            && Objects.equals(getFimMiddleToken(), that.getFimMiddleToken());
    }

    @Override
    public int hashCode() {
        return Objects.hash(
            getId(),
            getName(),
            getFormat(),
            getBaseUrl(),
            getModel(),
            getApiKey(),
            getTimeoutSeconds(),
            getHeadersJson(),
            fimEnabled,
            getFimPrefixToken(),
            getFimSuffixToken(),
            getFimMiddleToken()
        );
    }
}
