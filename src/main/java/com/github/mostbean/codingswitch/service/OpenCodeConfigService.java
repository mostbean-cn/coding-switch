package com.github.mostbean.codingswitch.service;

import com.github.mostbean.codingswitch.model.CliType;
import com.github.mostbean.codingswitch.model.Provider;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * OpenCode live configuration and OMO file management.
 */
@Service(Service.Level.APP)
public final class OpenCodeConfigService {

    private static final Logger LOG = Logger.getInstance(OpenCodeConfigService.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String SCHEMA_URL = "https://opencode.ai/config.json";

    public record OmoVariant(String category, String pluginName, String pluginPrefix, String fileName,
                             boolean hasCategories) {
    }

    public record OmoLocalFileData(JsonObject agents, JsonObject categories, JsonObject otherFields,
                                   Path filePath, String lastModified) {
    }

    public static final OmoVariant OMO_STANDARD = new OmoVariant(
            Provider.CATEGORY_OMO,
            "oh-my-opencode@latest",
            "oh-my-opencode",
            "oh-my-opencode.json",
            true);

    public static final OmoVariant OMO_SLIM = new OmoVariant(
            Provider.CATEGORY_OMO_SLIM,
            "oh-my-opencode-slim@latest",
            "oh-my-opencode-slim",
            "oh-my-opencode-slim.json",
            false);

    public static OpenCodeConfigService getInstance() {
        return ApplicationManager.getApplication().getService(OpenCodeConfigService.class);
    }

    public JsonObject readLiveConfig() {
        try {
            return readLiveConfigStrict();
        } catch (Exception e) {
            LOG.warn("Failed to read OpenCode config, fallback to empty object", e);
            return createEmptyLiveConfig();
        }
    }

    public JsonObject readLiveConfigStrict() throws IOException {
        Path path = getLiveConfigPath();
        if (!Files.exists(path)) {
            return createEmptyLiveConfig();
        }
        String content = Files.readString(path, StandardCharsets.UTF_8);
        if (content.isBlank()) {
            return createEmptyLiveConfig();
        }
        JsonElement parsed = JsonParser.parseString(content);
        if (!parsed.isJsonObject()) {
            throw new IOException("OpenCode config root must be a JSON object");
        }
        return parsed.getAsJsonObject();
    }

    public JsonObject getLiveProvidersObject() {
        JsonObject config = readLiveConfig();
        if (config.has("provider") && config.get("provider").isJsonObject()) {
            return config.getAsJsonObject("provider");
        }
        return new JsonObject();
    }

    public JsonObject getLiveProvidersObjectStrict() throws IOException {
        JsonObject config = readLiveConfigStrict();
        if (config.has("provider") && config.get("provider").isJsonObject()) {
            return config.getAsJsonObject("provider");
        }
        return new JsonObject();
    }

    public Set<String> getLiveProviderKeys() {
        return new LinkedHashSet<>(getLiveProvidersObject().keySet());
    }

    public Set<String> getLiveProviderKeysStrict() throws IOException {
        return new LinkedHashSet<>(getLiveProvidersObjectStrict().keySet());
    }

    public JsonObject getLiveProviderConfig(String providerKey) {
        if (providerKey == null || providerKey.isBlank()) {
            return null;
        }
        JsonObject providers = getLiveProvidersObject();
        if (!providers.has(providerKey) || !providers.get(providerKey).isJsonObject()) {
            return null;
        }
        return providers.getAsJsonObject(providerKey);
    }

    public boolean isProviderSynced(Provider provider) {
        if (provider == null || !provider.isOpenCodeCustomCategory() || provider.getProviderKey() == null) {
            return false;
        }
        JsonObject liveConfig = getLiveProviderConfig(provider.getProviderKey());
        return liveConfig != null && liveConfig.equals(provider.getSettingsConfig());
    }

    public void syncProvider(Provider provider) throws IOException {
        if (provider == null || !provider.isOpenCodeCustomCategory()) {
            return;
        }
        String providerKey = provider.getProviderKey();
        if (providerKey == null || providerKey.isBlank()) {
            throw new IOException("OpenCode providerKey is required");
        }

        JsonObject config = readLiveConfig();
        JsonObject providers = config.has("provider") && config.get("provider").isJsonObject()
                ? config.getAsJsonObject("provider")
                : new JsonObject();
        providers.add(providerKey, deepCopyObject(provider.getSettingsConfig()));
        config.add("provider", providers);
        writeLiveConfig(config);
    }

    public void removeProvider(String providerKey) throws IOException {
        if (providerKey == null || providerKey.isBlank()) {
            return;
        }

        JsonObject config = readLiveConfig();
        if (config.has("provider") && config.get("provider").isJsonObject()) {
            JsonObject providers = config.getAsJsonObject("provider");
            providers.remove(providerKey);
            if (providers.keySet().isEmpty()) {
                config.remove("provider");
            } else {
                config.add("provider", providers);
            }
        }
        writeLiveConfig(config);
    }

    public void applyOmoProvider(Provider provider) throws IOException {
        OmoVariant variant = getVariant(provider != null ? provider.getNormalizedCategory() : null);
        if (provider == null || variant == null) {
            throw new IOException("Unsupported OMO category");
        }

        JsonObject merged = buildMergedOmoConfig(provider);
        writeJsonFile(getOmoConfigPath(variant.category()), merged);

        OmoVariant otherVariant = Provider.CATEGORY_OMO.equals(variant.category()) ? OMO_SLIM : OMO_STANDARD;
        deleteOmoFiles(otherVariant);

        JsonObject liveConfig = readLiveConfig();
        JsonArray plugins = normalizePluginArray(liveConfig.get("plugin"));
        JsonArray updated = new JsonArray();
        for (JsonElement element : plugins) {
            if (!matchesPluginPrefix(element, OMO_STANDARD.pluginPrefix())
                    && !matchesPluginPrefix(element, OMO_SLIM.pluginPrefix())) {
                updated.add(element.deepCopy());
            }
        }
        updated.add(new JsonPrimitive(variant.pluginName()));
        liveConfig.add("plugin", updated);
        writeLiveConfig(liveConfig);
    }

    public void disableOmo(String category) throws IOException {
        OmoVariant variant = getVariant(category);
        if (variant == null) {
            return;
        }

        deleteOmoFiles(variant);

        JsonObject liveConfig = readLiveConfig();
        JsonArray plugins = normalizePluginArray(liveConfig.get("plugin"));
        JsonArray updated = new JsonArray();
        for (JsonElement element : plugins) {
            if (!matchesPluginPrefix(element, variant.pluginPrefix())) {
                updated.add(element.deepCopy());
            }
        }
        if (updated.size() == 0) {
            liveConfig.remove("plugin");
        } else {
            liveConfig.add("plugin", updated);
        }
        writeLiveConfig(liveConfig);
    }

    public boolean isOmoApplied(String category) {
        OmoVariant variant = getVariant(category);
        if (variant == null) {
            return false;
        }

        JsonArray plugins = normalizePluginArray(readLiveConfig().get("plugin"));
        boolean pluginEnabled = false;
        for (JsonElement element : plugins) {
            if (matchesPluginPrefix(element, variant.pluginPrefix())) {
                pluginEnabled = true;
                break;
            }
        }
        return pluginEnabled && hasAnyOmoFile(variant);
    }

    public JsonObject buildMergedOmoConfig(Provider provider) {
        if (provider == null) {
            return new JsonObject();
        }
        return buildMergedOmoConfig(provider.getNormalizedCategory(), provider.getSettingsConfig());
    }

    public JsonObject buildMergedOmoConfig(String category, JsonObject draftSettings) {
        JsonObject draft = draftSettings != null ? draftSettings : new JsonObject();
        JsonObject merged = new JsonObject();

        if (draft.has("otherFields") && draft.get("otherFields").isJsonObject()) {
            JsonObject otherFields = draft.getAsJsonObject("otherFields");
            for (String key : otherFields.keySet()) {
                merged.add(key, otherFields.get(key).deepCopy());
            }
        }
        if (draft.has("agents") && draft.get("agents").isJsonObject()) {
            merged.add("agents", draft.getAsJsonObject("agents").deepCopy());
        }
        if (!Provider.CATEGORY_OMO_SLIM.equals(category)
                && draft.has("categories") && draft.get("categories").isJsonObject()) {
            merged.add("categories", draft.getAsJsonObject("categories").deepCopy());
        }
        return merged;
    }

    public OmoLocalFileData readOmoLocalFile(String category) throws IOException {
        return readOmoLocalFile(resolveOmoLocalFilePath(category), category);
    }

    public OmoLocalFileData readOmoLocalFile(Path path, String category) throws IOException {
        if (path == null || !Files.exists(path)) {
            throw new NoSuchFileException("OMO config file not found");
        }
        String content = Files.readString(path, StandardCharsets.UTF_8);
        String cleaned = stripJsonComments(content);
        JsonElement parsed = JsonParser.parseString(cleaned);
        if (!parsed.isJsonObject()) {
            throw new IOException("OMO config must be a JSON object");
        }
        JsonObject root = parsed.getAsJsonObject();
        JsonObject agents = root.has("agents") && root.get("agents").isJsonObject()
                ? root.getAsJsonObject("agents").deepCopy()
                : new JsonObject();
        JsonObject categories = root.has("categories") && root.get("categories").isJsonObject()
                ? root.getAsJsonObject("categories").deepCopy()
                : new JsonObject();
        JsonObject otherFields = new JsonObject();
        for (String key : root.keySet()) {
            if (!"agents".equals(key) && !"categories".equals(key)) {
                otherFields.add(key, root.get(key).deepCopy());
            }
        }
        String lastModified = Files.getLastModifiedTime(path).toInstant().toString();
        return new OmoLocalFileData(agents, categories, otherFields, path, lastModified);
    }

    public JsonObject toOmoDraftSettings(OmoLocalFileData localFileData, String category) {
        JsonObject settings = new JsonObject();
        if (localFileData == null) {
            return settings;
        }
        if (localFileData.agents() != null && !localFileData.agents().keySet().isEmpty()) {
            settings.add("agents", localFileData.agents().deepCopy());
        }
        if (!Provider.CATEGORY_OMO_SLIM.equals(category)
                && localFileData.categories() != null
                && !localFileData.categories().keySet().isEmpty()) {
            settings.add("categories", localFileData.categories().deepCopy());
        }
        if (localFileData.otherFields() != null && !localFileData.otherFields().keySet().isEmpty()) {
            settings.add("otherFields", localFileData.otherFields().deepCopy());
        }
        return settings;
    }

    public Path getConfigDir() {
        return ConfigFileService.getInstance().getConfigDir(CliType.OPENCODE);
    }

    public Path getLiveConfigPath() {
        return ConfigFileService.getInstance().getProviderConfigPath(CliType.OPENCODE);
    }

    public Path getOmoConfigPath(String category) {
        OmoVariant variant = getVariant(category);
        return variant == null ? null : getConfigDir().resolve(variant.fileName());
    }

    public Path resolveOmoLocalFilePath(String category) {
        OmoVariant variant = getVariant(category);
        if (variant == null) {
            return null;
        }
        List<Path> candidates = buildOmoCandidatePaths(variant);
        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    public OmoVariant getVariant(String category) {
        if (Provider.CATEGORY_OMO.equals(category)) {
            return OMO_STANDARD;
        }
        if (Provider.CATEGORY_OMO_SLIM.equals(category)) {
            return OMO_SLIM;
        }
        return null;
    }

    public void writeLiveConfig(JsonObject config) throws IOException {
        JsonObject safeConfig = config != null ? config.deepCopy() : createEmptyLiveConfig();
        if (!safeConfig.has("$schema")) {
            safeConfig.addProperty("$schema", SCHEMA_URL);
        }
        ConfigFileService.getInstance().writeJsonFile(getLiveConfigPath(), safeConfig);
    }

    private JsonObject createEmptyLiveConfig() {
        JsonObject config = new JsonObject();
        config.addProperty("$schema", SCHEMA_URL);
        return config;
    }

    private void writeJsonFile(Path path, JsonObject json) throws IOException {
        ConfigFileService.getInstance().writeFile(path, GSON.toJson(json));
    }

    private JsonArray normalizePluginArray(JsonElement pluginElement) {
        JsonArray plugins = new JsonArray();
        if (pluginElement == null || pluginElement.isJsonNull()) {
            return plugins;
        }
        if (pluginElement.isJsonArray()) {
            for (JsonElement element : pluginElement.getAsJsonArray()) {
                plugins.add(element.deepCopy());
            }
            return plugins;
        }
        plugins.add(pluginElement.deepCopy());
        return plugins;
    }

    private boolean matchesPluginPrefix(JsonElement element, String pluginPrefix) {
        if (element == null || !element.isJsonPrimitive()) {
            return false;
        }
        String name = element.getAsString();
        String normalized = normalizePluginName(name);
        return normalized.equals(pluginPrefix);
    }

    private String normalizePluginName(String pluginName) {
        if (pluginName == null) {
            return "";
        }
        String normalized = pluginName.trim().toLowerCase(Locale.ROOT);
        int versionIndex = normalized.indexOf('@');
        return versionIndex >= 0 ? normalized.substring(0, versionIndex) : normalized;
    }

    private void deleteOmoFiles(OmoVariant variant) throws IOException {
        for (Path candidate : buildOmoCandidatePaths(variant)) {
            Files.deleteIfExists(candidate);
        }
    }

    private boolean hasAnyOmoFile(OmoVariant variant) {
        for (Path candidate : buildOmoCandidatePaths(variant)) {
            if (Files.exists(candidate)) {
                return true;
            }
        }
        return false;
    }

    private List<Path> buildOmoCandidatePaths(OmoVariant variant) {
        List<Path> candidates = new ArrayList<>();
        Path jsonPath = getConfigDir().resolve(variant.fileName());
        candidates.add(jsonPath);
        if (jsonPath.toString().endsWith(".json")) {
            String jsoncName = jsonPath.getFileName().toString().replaceAll("\\.json$", ".jsonc");
            candidates.add(jsonPath.resolveSibling(jsoncName));
        }
        return candidates;
    }

    private JsonObject deepCopyObject(JsonObject source) {
        return source != null ? source.deepCopy() : new JsonObject();
    }

    public static String stripJsonComments(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }

        StringBuilder result = new StringBuilder(input.length());
        boolean inString = false;
        boolean escaped = false;

        for (int i = 0; i < input.length(); i++) {
            char current = input.charAt(i);

            if (inString) {
                result.append(current);
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '"') {
                    inString = false;
                }
                continue;
            }

            if (current == '"') {
                inString = true;
                result.append(current);
                continue;
            }

            if (current == '/' && i + 1 < input.length()) {
                char next = input.charAt(i + 1);
                if (next == '/') {
                    i += 2;
                    while (i < input.length() && input.charAt(i) != '\n') {
                        i++;
                    }
                    if (i < input.length()) {
                        result.append('\n');
                    }
                    continue;
                }
                if (next == '*') {
                    i += 2;
                    while (i + 1 < input.length() && !(input.charAt(i) == '*' && input.charAt(i + 1) == '/')) {
                        i++;
                    }
                    i++;
                    continue;
                }
            }

            result.append(current);
        }
        return result.toString();
    }

    public Map<String, JsonObject> readLiveProvidersAsMap() {
        JsonObject providers = getLiveProvidersObject();
        Map<String, JsonObject> result = new LinkedHashMap<>();
        for (String key : providers.keySet()) {
            if (providers.get(key).isJsonObject()) {
                result.put(key, providers.getAsJsonObject(key).deepCopy());
            }
        }
        return result;
    }

    public String nowTimestamp() {
        return Instant.now().toString();
    }
}
