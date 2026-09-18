package com.github.mostbean.codingswitch.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pi 配置辅助：内置目录走 auth.json + settings.json，
 * 自定义兼容接口额外写入 models.json 的 coding-switch 段。
 */
public final class PiConfigSupport {

    public static final String CUSTOM_PROVIDER_ID = "coding-switch";
    public static final String API_OPENAI_COMPLETIONS = "openai-completions";
    public static final String API_OPENAI_RESPONSES = "openai-responses";
    public static final String API_ANTHROPIC_MESSAGES = "anthropic-messages";
    public static final String DEFAULT_API = API_OPENAI_COMPLETIONS;
    public static final List<String> APIS = List.of(
            API_OPENAI_COMPLETIONS,
            API_OPENAI_RESPONSES,
            API_ANTHROPIC_MESSAGES
    );

    public record BuiltinProvider(
            String id,
            String displayName,
            String defaultModel,
            String probeBaseUrl,
            String probeApi
    ) {
    }

    public record ParsedConfig(
            String providerId,
            String apiKey,
            String baseUrl,
            String api,
            String model,
            boolean custom
    ) {
        public ParsedConfig {
            providerId = providerId == null || providerId.isBlank() ? CUSTOM_PROVIDER_ID : providerId.trim();
            apiKey = apiKey == null ? "" : apiKey.trim();
            api = normalizeApi(api);
            baseUrl = normalizeBaseUrl(baseUrl, api);
            model = model == null ? "" : model.trim();
        }
    }

    private static final List<BuiltinProvider> BUILTINS = List.of(
            new BuiltinProvider(
                    "deepseek",
                    "DeepSeek",
                    "deepseek-v4-pro",
                    "https://api.deepseek.com/v1",
                    API_OPENAI_COMPLETIONS),
            new BuiltinProvider(
                    "zai-coding-cn",
                    "智谱 GLM",
                    "glm-5.1",
                    "https://open.bigmodel.cn/api/coding/paas/v4",
                    API_OPENAI_COMPLETIONS),
            new BuiltinProvider(
                    "kimi-coding",
                    "Kimi",
                    "kimi-for-coding",
                    "https://api.kimi.com/coding",
                    API_OPENAI_COMPLETIONS),
            new BuiltinProvider(
                    "minimax-cn",
                    "MiniMax",
                    "MiniMax-M2.5",
                    "https://api.minimaxi.com/v1",
                    API_OPENAI_COMPLETIONS),
            new BuiltinProvider(
                    "qwen-token-plan-cn",
                    "阿里 Plan",
                    "qwen3.6-plus",
                    "https://coding.dashscope.aliyuncs.com/v1",
                    API_OPENAI_COMPLETIONS),
            new BuiltinProvider(
                    "xiaomi-token-plan-cn",
                    "MiMo Plan",
                    "mimo-v2.5-pro",
                    "https://token-plan-cn.xiaomimimo.com/v1",
                    API_OPENAI_COMPLETIONS)
    );

    private PiConfigSupport() {
    }

    public static List<BuiltinProvider> builtins() {
        return BUILTINS;
    }

    public static BuiltinProvider findBuiltin(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            return null;
        }
        String id = providerId.trim();
        for (BuiltinProvider builtin : BUILTINS) {
            if (builtin.id().equalsIgnoreCase(id)) {
                return builtin;
            }
        }
        return null;
    }

    public static boolean isCustomProvider(String providerId) {
        return findBuiltin(providerId) == null;
    }

    public static ParsedConfig parse(JsonObject settingsConfig) {
        JsonObject safe = settingsConfig != null ? settingsConfig : new JsonObject();
        JsonObject settings = object(safe, "settings");
        JsonObject modelsRoot = object(safe, "models");
        JsonObject providers = object(modelsRoot, "providers");
        JsonObject auth = object(safe, "auth");

        String providerId = firstNotBlank(
                getString(settings, "defaultProvider"),
                firstProviderId(providers),
                firstProviderId(auth),
                CUSTOM_PROVIDER_ID);
        boolean custom = isCustomProvider(providerId);
        JsonObject providerModels = object(providers, providerId);
        String apiKey = extractApiKey(auth, providerId);
        if (apiKey.isBlank()) {
            apiKey = firstNotBlank(getString(providerModels, "apiKey"));
        }
        BuiltinProvider builtin = findBuiltin(providerId);
        String baseUrl = firstNotBlank(
                getString(providerModels, "baseUrl"),
                builtin != null ? builtin.probeBaseUrl() : "");
        String api = firstNotBlank(
                getString(providerModels, "api"),
                builtin != null ? builtin.probeApi() : DEFAULT_API);
        String model = firstNotBlank(
                getString(settings, "defaultModel"),
                firstModelId(providerModels),
                builtin != null ? builtin.defaultModel() : "");
        return new ParsedConfig(providerId, apiKey, baseUrl, api, model, custom);
    }

    public static JsonObject buildSettingsConfig(
            String providerId,
            String apiKey,
            String baseUrl,
            String api,
            String model
    ) {
        String safeProviderId = sanitizeProviderId(providerId);
        boolean custom = isCustomProvider(safeProviderId);
        JsonObject config = new JsonObject();
        config.add("auth", buildAuth(safeProviderId, apiKey));
        config.add("models", custom ? buildModels(safeProviderId, baseUrl, api, apiKey, model) : new JsonObject());
        config.add("settings", buildSettings(safeProviderId, model));
        return config;
    }

    public static JsonObject buildOfficialConfig() {
        JsonObject config = new JsonObject();
        config.add("auth", new JsonObject());
        config.add("models", new JsonObject());
        config.add("settings", new JsonObject());
        return config;
    }

    public static JsonObject buildAuth(String providerId, String apiKey) {
        JsonObject auth = new JsonObject();
        String safeProviderId = sanitizeProviderId(providerId);
        String key = apiKey == null ? "" : apiKey.trim();
        if (key.isBlank()) {
            return auth;
        }
        JsonObject credential = new JsonObject();
        credential.addProperty("type", "api_key");
        credential.addProperty("key", key);
        auth.add(safeProviderId, credential);
        return auth;
    }

    public static JsonObject buildModels(
            String providerId,
            String baseUrl,
            String api,
            String apiKey,
            String model
    ) {
        JsonObject root = new JsonObject();
        JsonObject providers = new JsonObject();
        JsonObject provider = new JsonObject();
        String safeBaseUrl = normalizeBaseUrl(baseUrl, api);
        if (!safeBaseUrl.isBlank()) {
            provider.addProperty("baseUrl", safeBaseUrl);
        }
        provider.addProperty("api", normalizeApi(api));
        if (apiKey != null && !apiKey.isBlank()) {
            provider.addProperty("apiKey", apiKey.trim());
        }
        provider.addProperty("authHeader", true);
        JsonArray models = new JsonArray();
        if (model != null && !model.isBlank()) {
            JsonObject modelDef = new JsonObject();
            modelDef.addProperty("id", model.trim());
            modelDef.addProperty("name", model.trim());
            models.add(modelDef);
        }
        provider.add("models", models);
        providers.add(sanitizeProviderId(providerId), provider);
        root.add("providers", providers);
        return root;
    }

    public static JsonObject buildSettings(String providerId, String model) {
        JsonObject settings = new JsonObject();
        settings.addProperty("defaultProvider", sanitizeProviderId(providerId));
        if (model != null && !model.isBlank()) {
            settings.addProperty("defaultModel", model.trim());
        }
        return settings;
    }

    public static boolean hasCustomModelConfig(JsonObject settingsConfig) {
        ParsedConfig parsed = parse(settingsConfig);
        return parsed.custom() && (!parsed.baseUrl().isBlank() || !parsed.model().isBlank() || !parsed.apiKey().isBlank());
    }

    public static String extractApiKey(JsonObject auth, String providerId) {
        if (auth == null || providerId == null || providerId.isBlank() || !auth.has(providerId)) {
            return "";
        }
        JsonElement value = auth.get(providerId);
        if (value == null || value.isJsonNull()) {
            return "";
        }
        if (value.isJsonPrimitive()) {
            return value.getAsString().trim();
        }
        if (value.isJsonObject()) {
            return firstNotBlank(getString(value.getAsJsonObject(), "key"));
        }
        return "";
    }

    public static String normalizeApi(String api) {
        if (api == null || api.isBlank()) {
            return DEFAULT_API;
        }
        String normalized = api.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        if ("openai-chat".equals(normalized)
                || "chat-completions".equals(normalized)
                || "openai-completion".equals(normalized)
                || "chat".equals(normalized)) {
            return API_OPENAI_COMPLETIONS;
        }
        if ("responses".equals(normalized)
                || "openai-response".equals(normalized)
                || "response".equals(normalized)) {
            return API_OPENAI_RESPONSES;
        }
        if ("anthropic".equals(normalized)
                || "anthropic-message".equals(normalized)
                || "messages".equals(normalized)) {
            return API_ANTHROPIC_MESSAGES;
        }
        return APIS.contains(normalized) ? normalized : DEFAULT_API;
    }

    public static String normalizeBaseUrl(String baseUrl) {
        return normalizeBaseUrl(baseUrl, null);
    }

    /**
     * Anthropic Messages 由 SDK 再拼接 /v1/messages。
     * 用户若把 Base URL 写成 .../v1 或 .../v1/messages，运行时会变成 /v1/v1/messages 导致 404。
     */
    public static String normalizeBaseUrl(String baseUrl, String api) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "";
        }
        String trimmed = baseUrl.trim().replaceAll("/+$", "");
        if (API_ANTHROPIC_MESSAGES.equals(normalizeApi(api))) {
            return stripAnthropicEndpointSuffix(trimmed);
        }
        return trimmed;
    }

    static String stripAnthropicEndpointSuffix(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "";
        }
        String current = baseUrl.trim().replaceAll("/+$", "");
        String lower = current.toLowerCase(Locale.ROOT);
        if (lower.endsWith("/v1/messages")) {
            current = current.substring(0, current.length() - "/v1/messages".length());
        } else if (lower.endsWith("/messages")) {
            current = current.substring(0, current.length() - "/messages".length());
        } else if (lower.endsWith("/v1")) {
            current = current.substring(0, current.length() - "/v1".length());
        }
        return current.replaceAll("/+$", "");
    }

    public static String sanitizeProviderId(String raw) {
        if (raw == null || raw.isBlank()) {
            return CUSTOM_PROVIDER_ID;
        }
        String trimmed = raw.trim();
        if (findBuiltin(trimmed) != null || CUSTOM_PROVIDER_ID.equals(trimmed)) {
            return findBuiltin(trimmed) != null ? findBuiltin(trimmed).id() : CUSTOM_PROVIDER_ID;
        }
        StringBuilder out = new StringBuilder();
        boolean previousDash = false;
        for (char ch : trimmed.toLowerCase(Locale.ROOT).toCharArray()) {
            if (Character.isLetterOrDigit(ch) || ch == '_' || ch == '-') {
                out.append(ch);
                previousDash = false;
            } else if (!previousDash && out.length() > 0) {
                out.append('-');
                previousDash = true;
            }
        }
        String sanitized = out.toString().replaceAll("^-+|-+$", "");
        return sanitized.isBlank() ? CUSTOM_PROVIDER_ID : sanitized;
    }

    public static String firstNotBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private static JsonObject object(JsonObject parent, String key) {
        if (parent == null || key == null || !parent.has(key) || parent.get(key).isJsonNull()) {
            return new JsonObject();
        }
        JsonElement value = parent.get(key);
        return value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
    }

    private static String getString(JsonObject json, String key) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
            return "";
        }
        try {
            String value = json.get(key).getAsString();
            return value == null ? "" : value.trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String firstProviderId(JsonObject object) {
        if (object == null || object.keySet().isEmpty()) {
            return "";
        }
        return new ArrayList<>(object.keySet()).get(0);
    }

    private static String firstModelId(JsonObject provider) {
        if (provider == null || !provider.has("models") || !provider.get("models").isJsonArray()) {
            return "";
        }
        JsonArray models = provider.getAsJsonArray("models");
        if (models.isEmpty() || !models.get(0).isJsonObject()) {
            return "";
        }
        return getString(models.get(0).getAsJsonObject(), "id");
    }
}
