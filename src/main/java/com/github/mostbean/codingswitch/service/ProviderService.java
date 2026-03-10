package com.github.mostbean.codingswitch.service;

import com.github.mostbean.codingswitch.model.CliType;
import com.github.mostbean.codingswitch.model.Provider;
import com.github.mostbean.codingswitch.model.Provider.AuthMode;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Provider 管理服务。
 * 使用 IntelliJ PersistentStateComponent 持久化 Provider 列表，
 * 激活 Provider 时按 CLI 类型将 settingsConfig 写入对应的配置文件，
 * 输出格式与 cc-switch 完全一致。
 */
@Service(Service.Level.APP)
@State(name = "CodingSwitchProviders", storages = @Storage("coding-switch-providers.xml"))
public final class ProviderService implements PersistentStateComponent<ProviderService.State> {

    private static final Logger LOG = Logger.getInstance(ProviderService.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static class State {
        public String providersJson = "[]";
    }

    private State myState = new State();
    private final List<Runnable> changeListeners = new ArrayList<>();

    public static ProviderService getInstance() {
        return ApplicationManager.getApplication().getService(ProviderService.class);
    }

    @Override
    public @Nullable State getState() {
        myState.providersJson = GSON.toJson(getProviders());
        return myState;
    }

    @Override
    public void loadState(@NotNull State state) {
        myState = state;
    }

    // =====================================================================
    // CRUD
    // =====================================================================

    public List<Provider> getProviders() {
        try {
            List<Provider> list = GSON.fromJson(myState.providersJson,
                    new TypeToken<List<Provider>>() {
                    }.getType());
            List<Provider> providers = list != null ? list : new ArrayList<>();
            providers.forEach(this::normalizeProvider);
            providers.sort(Comparator
                    .comparing((Provider p) -> p.getCliType() != null ? p.getCliType().getDisplayName() : "",
                            String.CASE_INSENSITIVE_ORDER)
                    .thenComparing((Provider p) -> p.getCreatedAt() != null ? p.getCreatedAt() : 0L)
                    .thenComparing(Provider::getId, Comparator.nullsLast(String::compareTo)));
            return providers;
        } catch (Exception e) {
            LOG.warn("Failed to parse providers", e);
            return new ArrayList<>();
        }
    }

    public List<Provider> getProvidersByType(CliType cliType) {
        return getProviders().stream()
                .filter(p -> p.getCliType() == cliType)
                .toList();
    }

    public Optional<Provider> getActiveProvider(CliType cliType) {
        return getProviders().stream()
                .filter(p -> p.getCliType() == cliType && p.isActive())
                .findFirst();
    }

    public void addProvider(Provider provider) {
        List<Provider> providers = new ArrayList<>(getProviders());
        providers.add(provider);
        saveProviders(providers);
    }

    public void updateProvider(Provider provider) {
        List<Provider> providers = new ArrayList<>(getProviders());
        Provider existing = providers.stream()
                .filter(p -> p.getId().equals(provider.getId()))
                .findFirst()
                .orElse(null);

        if (existing != null) {
            provider.setActive(existing.isActive());
            if (existing.isActive()) {
                boolean syncRelevantChanged = !Objects.equals(existing.getCliType(), provider.getCliType())
                        || !Objects.equals(existing.getAuthMode(), provider.getAuthMode())
                        || !Objects.equals(existing.getName(), provider.getName())
                        || !Objects.equals(existing.getSettingsConfig(), provider.getSettingsConfig());
                provider.setPendingActivation(syncRelevantChanged || existing.isPendingActivation());
            } else {
                provider.setPendingActivation(false);
            }
        }

        providers.replaceAll(p -> p.getId().equals(provider.getId()) ? provider : p);
        saveProviders(providers);
    }

    public void removeProvider(String providerId) {
        List<Provider> providers = new ArrayList<>(getProviders());
        providers.removeIf(p -> p.getId().equals(providerId));
        saveProviders(providers);
    }

    public void duplicateProvider(String providerId) {
        getProviders().stream()
                .filter(p -> p.getId().equals(providerId))
                .findFirst()
                .ifPresent(p -> addProvider(p.copy()));
    }

    // =====================================================================
    // 激活 Provider — 写入 CLI 配置文件（输出与 cc-switch 一致）
    // =====================================================================

    public void activateProvider(String providerId) throws IOException {
        List<Provider> providers = new ArrayList<>(getProviders());
        Provider target = null;

        for (Provider p : providers) {
            if (p.getId().equals(providerId)) {
                target = p;
            }
        }

        if (target == null) {
            throw new IllegalArgumentException("Provider not found: " + providerId);
        }

        // 同一 CLI 类型下只能有一个 active（OpenCode 除外，它是 additive 模式）
        for (Provider p : providers) {
            if (p.getCliType() == target.getCliType()) {
                boolean shouldActivate = p.getId().equals(providerId);
                p.setActive(shouldActivate);
                p.setPendingActivation(false);
            }
        }

        saveProviders(providers);
        writeToLiveConfig(target);
    }

    /**
     * 根据 CLI 类型，将 settingsConfig 写入对应的 live 配置文件。
     * 输出格式与 cc-switch 完全一致。
     */
    private void writeToLiveConfig(Provider provider) throws IOException {
        ConfigFileService svc = ConfigFileService.getInstance();
        CliType cliType = provider.getCliType();
        JsonObject config = provider.getSettingsConfig();

        switch (cliType) {
            case CLAUDE -> writeClaudeLive(svc, config);
            case CODEX -> {
                if (provider.getAuthMode() == AuthMode.OFFICIAL_LOGIN) {
                    writeCodexOfficialLive(svc, config);
                } else {
                    writeCodexLive(svc, config);
                }
            }
            case GEMINI -> writeGeminiLive(svc, config);
            case OPENCODE -> writeOpenCodeLive(svc, config, provider.getName());
        }
    }

    /**
     * Claude: 将 settingsConfig.env 合并写入 ~/.claude/settings.json
     * cc-switch 格式: { "env": { "ANTHROPIC_BASE_URL": "...", "ANTHROPIC_AUTH_TOKEN":
     * "...", ... } }
     */
    private void writeClaudeLive(ConfigFileService svc, JsonObject config) throws IOException {
        Path path = svc.getProviderConfigPath(CliType.CLAUDE);
        JsonObject existing = svc.readJsonFile(path);

        if (config.has("env")) {
            JsonObject env = existing.has("env") ? existing.getAsJsonObject("env") : new JsonObject();
            JsonObject newEnv = config.getAsJsonObject("env");

            // 清除旧的 Provider 相关字段
            env.remove("ANTHROPIC_AUTH_TOKEN");
            env.remove("ANTHROPIC_API_KEY");
            env.remove("ANTHROPIC_BASE_URL");
            env.remove("ANTHROPIC_MODEL");
            env.remove("ANTHROPIC_DEFAULT_HAIKU_MODEL");
            env.remove("ANTHROPIC_DEFAULT_SONNET_MODEL");
            env.remove("ANTHROPIC_DEFAULT_OPUS_MODEL");
            env.remove("CLAUDE_CODE_EFFORT_LEVEL");

            // 写入新值
            for (String key : newEnv.keySet()) {
                env.add(key, newEnv.get(key));
            }
            existing.add("env", env);
        }

        if (config.has("effortLevel") && !config.get("effortLevel").isJsonNull()) {
            existing.add("effortLevel", config.get("effortLevel"));
        } else {
            existing.remove("effortLevel");
        }

        if (config.has("dangerouslySkipPermissions") && config.get("dangerouslySkipPermissions").getAsBoolean()) {
            existing.addProperty("dangerouslySkipPermissions", true);
        } else {
            existing.remove("dangerouslySkipPermissions");
        }

        if (config.has("skipDangerousModePermissionPrompt") && config.get("skipDangerousModePermissionPrompt").getAsBoolean()) {
            existing.addProperty("skipDangerousModePermissionPrompt", true);
        } else {
            existing.remove("skipDangerousModePermissionPrompt");
        }

        svc.writeJsonFile(path, existing);
    }
    /**
     * Codex: 将 auth 写入 ~/.codex/auth.json，config 写入 ~/.codex/config.toml
     * cc-switch 格式: { "auth": { "OPENAI_API_KEY": "..." }, "config": "toml string"
     * }
     */
    private void writeCodexLive(ConfigFileService svc, JsonObject config) throws IOException {
        // 写 auth.json
        if (hasCodexApiKey(config)) {
            Path authPath = svc.getProviderConfigPath(CliType.CODEX);
            svc.writeJsonFile(authPath, config.getAsJsonObject("auth"));
        }

        // 写 config.toml
        if (config.has("config")) {
            Path tomlPath = svc.getConfigDir(CliType.CODEX).resolve("config.toml");
            String providerToml = config.get("config").getAsString().trim();
            String managedBlock = "# >>> coding-switch:provider:start\n"
                    + providerToml + "\n"
                    + "# <<< coding-switch:provider:end\n";
            String existing = svc.readFile(tomlPath);
            String withoutManagedBlock = removeManagedBlock(
                    existing,
                    "# >>> coding-switch:provider:start",
                    "# <<< coding-switch:provider:end");
            String sanitized = removeConflictingCodexProviderEntries(withoutManagedBlock, providerToml);
            String merged = prependManagedBlock(sanitized, managedBlock);
            svc.writeFile(tomlPath, merged);
        }
    }

    private void writeCodexOfficialLive(ConfigFileService svc, JsonObject config) throws IOException {
        Path tomlPath = svc.getConfigDir(CliType.CODEX).resolve("config.toml");
        boolean existed = Files.exists(tomlPath);
        String existing = svc.readFile(tomlPath);
        String withoutManagedBlock = removeManagedBlock(
                existing,
                "# >>> coding-switch:provider:start",
                "# <<< coding-switch:provider:end");
        String providerToml = config != null && config.has("config") && !config.get("config").isJsonNull()
                ? config.get("config").getAsString().trim()
                : "";
        String sanitized = removeConflictingCodexProviderEntries(withoutManagedBlock, providerToml);
        String finalContent = providerToml.isBlank()
                ? sanitized.stripLeading()
                : prependManagedBlock(
                        sanitized,
                        "# >>> coding-switch:provider:start\n"
                                + providerToml + "\n"
                                + "# <<< coding-switch:provider:end\n");
        if (!existed && finalContent.isBlank()) {
            return;
        }
        if (existing.equals(finalContent)) {
            return;
        }
        svc.writeFile(tomlPath, finalContent);
    }

    /**
     * Gemini: 将 settingsConfig.env 写入 ~/.gemini/.env（KEY=VALUE 格式）
     * cc-switch 格式: { "env": { "GEMINI_API_KEY": "...", "GOOGLE_GEMINI_BASE_URL":
     * "...", ... } }
     */
    private void writeGeminiLive(ConfigFileService svc, JsonObject config) throws IOException {
        Path path = svc.getProviderConfigPath(CliType.GEMINI);

        if (config.has("env")) {
            JsonObject env = config.getAsJsonObject("env");
            StringBuilder sb = new StringBuilder();
            for (String key : env.keySet()) {
                sb.append(key).append("=").append(env.get(key).getAsString()).append("\n");
            }
            svc.writeFile(path, sb.toString());
        }
    }

    /**
     * OpenCode: 将 Provider 配置写入 ~/.config/opencode/opencode.json 的 provider 段
     * cc-switch 格式: { "npm": "...", "options": {...}, "models": {...} }
     */
    private void writeOpenCodeLive(ConfigFileService svc, JsonObject config, String name) throws IOException {
        Path path = svc.getProviderConfigPath(CliType.OPENCODE);
        JsonObject existing = svc.readJsonFile(path);

        JsonObject provider = existing.has("provider")
                ? existing.getAsJsonObject("provider")
                : new JsonObject();

        // OpenCode 用 provider name 作为 key
        provider.add(name, config);
        existing.add("provider", provider);

        svc.writeJsonFile(path, existing);
    }

    // =====================================================================
    // 内部工具
    // =====================================================================

    private void saveProviders(List<Provider> providers) {
        myState.providersJson = GSON.toJson(providers);
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

    private void normalizeProvider(Provider provider) {
        if (provider == null || provider.getStoredAuthMode() != null) {
            return;
        }
        provider.setAuthMode(Provider.inferAuthMode(provider.getCliType(), provider.getSettingsConfig()));
    }

    private static boolean hasCodexApiKey(JsonObject config) {
        if (config == null || !config.has("auth") || !config.get("auth").isJsonObject()) {
            return false;
        }
        JsonObject auth = config.getAsJsonObject("auth");
        if (!auth.has("OPENAI_API_KEY") || auth.get("OPENAI_API_KEY").isJsonNull()) {
            return false;
        }
        return !auth.get("OPENAI_API_KEY").getAsString().isBlank();
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

    private static String prependManagedBlock(String existing, String block) {
        String safeExisting = existing == null ? "" : existing;
        if (safeExisting.isBlank()) {
            return block;
        }
        return block + "\n" + safeExisting.stripLeading();
    }

    private static String removeConflictingCodexProviderEntries(String content, String managedProviderToml) {
        String safe = content == null ? "" : content;
        if (safe.isBlank()) {
            return safe;
        }

        Set<String> managedProviderNames = extractManagedProviderNames(managedProviderToml);
        Set<String> managedRootKeys = Set.of(
                "model_provider",
                "model",
                "model_reasoning_effort",
                "model_context_window",
                "model_auto_compact_token_limit",
                "multi_agent",
                "fast_mode",
                "disable_response_storage");

        StringBuilder out = new StringBuilder();
        String currentSection = null;
        boolean dropCurrentSection = false;

        for (String rawLine : safe.split("\n", -1)) {
            String line = rawLine.trim();

            if (line.startsWith("[") && line.endsWith("]")) {
                currentSection = line.substring(1, line.length() - 1).trim();
                dropCurrentSection = isManagedProviderSection(currentSection, managedProviderNames);
            }

            if (dropCurrentSection) {
                continue;
            }

            String key = parseTomlKey(rawLine);
            if (currentSection == null && key != null && managedRootKeys.contains(key)) {
                continue;
            }

            out.append(rawLine).append("\n");
        }
        return out.toString();
    }

    private static Set<String> extractManagedProviderNames(String providerToml) {
        Set<String> names = new HashSet<>();
        if (providerToml == null || providerToml.isBlank()) {
            return names;
        }
        for (String rawLine : providerToml.split("\n")) {
            String line = rawLine.trim();
            if (!line.startsWith("[") || !line.endsWith("]")) {
                continue;
            }
            String section = line.substring(1, line.length() - 1).trim();
            if (section.startsWith("model_providers.")) {
                String suffix = section.substring("model_providers.".length()).trim();
                if ((suffix.startsWith("'") && suffix.endsWith("'"))
                        || (suffix.startsWith("\"") && suffix.endsWith("\""))) {
                    suffix = suffix.substring(1, suffix.length() - 1);
                }
                if (!suffix.isBlank()) {
                    names.add(suffix);
                }
            }
        }
        return names;
    }

    private static boolean isManagedProviderSection(String section, Set<String> managedProviderNames) {
        if (managedProviderNames == null || managedProviderNames.isEmpty()) {
            return false;
        }
        for (String name : managedProviderNames) {
            String base = "model_providers." + name;
            String singleQuoted = "model_providers.'" + name + "'";
            String doubleQuoted = "model_providers.\"" + name + "\"";
            if (section.equals(base) || section.startsWith(base + ".")
                    || section.equals(singleQuoted) || section.startsWith(singleQuoted + ".")
                    || section.equals(doubleQuoted) || section.startsWith(doubleQuoted + ".")) {
                return true;
            }
        }
        return false;
    }

    private static String parseTomlKey(String line) {
        int commentIdx = line.indexOf('#');
        String raw = commentIdx >= 0 ? line.substring(0, commentIdx) : line;
        int eq = raw.indexOf('=');
        if (eq <= 0) {
            return null;
        }
        String key = raw.substring(0, eq).trim();
        if (key.isEmpty() || key.contains(" ") || key.contains("\t")) {
            return null;
        }
        return key;
    }
}
