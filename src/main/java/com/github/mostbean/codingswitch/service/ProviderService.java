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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private CodexActivationResult lastCodexActivationResult = CodexActivationResult.notApplicable();
    private GrokActivationResult lastGrokActivationResult = GrokActivationResult.notApplicable();
    private PiActivationResult lastPiActivationResult = PiActivationResult.notApplicable();
    private AntigravityAuthSnapshotService.RestoreResult lastAntigravityActivationResult;

    public static ProviderService getInstance() {
        return ApplicationManager.getApplication().getService(ProviderService.class);
    }

    @Override
    public @Nullable State getState() {
        return myState;
    }

    @Override
    public void loadState(@NotNull State state) {
        myState = normalizeState(state);
    }

    // =====================================================================
    // CRUD
    // =====================================================================

    public List<Provider> getProviders() {
        try {
            State activeState = getActiveState();
            List<Provider> list = GSON.fromJson(activeState.providersJson,
                    new TypeToken<List<Provider>>() {
                    }.getType());
            List<Provider> providers = list != null ? list : new ArrayList<>();
            providers.forEach(this::normalizeProvider);
            providers.sort(Comparator
                    .comparing((Provider p) -> p.getCliType() != null ? p.getCliType().getDisplayName() : "",
                            String.CASE_INSENSITIVE_ORDER)
                    .thenComparing((Provider p) -> p.getDisplayOrder() != null ? p.getDisplayOrder() : Integer.MAX_VALUE)
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
        Provider existing = providers.stream()
                .filter(p -> p.getId().equals(providerId))
                .findFirst()
                .orElse(null);
        if (existing != null) {
            CodexAuthSnapshotService.getInstance().clearSnapshot(existing);
            AntigravityAuthSnapshotService.getInstance().clearSnapshot(existing);
            PiAuthSnapshotService.getInstance().clearSnapshot(existing);
        } else {
            CodexAuthSnapshotService.getInstance().clearSnapshot(providerId);
            AntigravityAuthSnapshotService.getInstance().clearSnapshot(providerId);
            PiAuthSnapshotService.getInstance().clearSnapshot(providerId);
        }
        providers.removeIf(p -> p.getId().equals(providerId));
        saveProviders(providers);
    }

    public void duplicateProvider(String providerId) {
        getProviders().stream()
                .filter(p -> p.getId().equals(providerId))
                .findFirst()
                .ifPresent(p -> addProvider(p.copy()));
    }

    public void reorderProviders(List<String> orderedProviderIds) {
        List<Provider> providers = new ArrayList<>(getProviders());
        Map<String, Integer> orderMap = new LinkedHashMap<>();
        for (int i = 0; i < orderedProviderIds.size(); i++) {
            orderMap.put(orderedProviderIds.get(i), i);
        }

        for (Provider provider : providers) {
            Integer order = orderMap.get(provider.getId());
            provider.setDisplayOrder(order != null ? order : Integer.MAX_VALUE);
        }

        saveProviders(providers);
    }

    // =====================================================================
    // 激活 Provider — 写入 CLI 配置文件（输出与 cc-switch 一致）
    // =====================================================================

    public void activateProvider(String providerId) throws IOException {
        List<Provider> providers = new ArrayList<>(getProviders());
        Provider target = null;
        Provider activeCodex = findActiveProvider(providers, CliType.CODEX);
        Provider activeGrok = findActiveProvider(providers, CliType.GROK);
        Provider activePi = findActiveProvider(providers, CliType.PI);
        Provider activeAntigravity = findActiveProvider(providers, CliType.ANTIGRAVITY);

        for (Provider p : providers) {
            if (p.getId().equals(providerId)) {
                target = p;
            }
        }

        if (target == null) {
            throw new IllegalArgumentException("Provider not found: " + providerId);
        }
        captureCurrentCodexSnapshot(target, activeCodex);
        captureCurrentGrokSnapshot(target, activeGrok);
        captureCurrentPiSnapshot(target, activePi);
        captureCurrentAntigravitySnapshot(target, activeAntigravity);

        // 同一 CLI 类型下只能有一个 active。OpenCode 是 additive 模式，状态以 live 配置为准。
        for (Provider p : providers) {
            if (p.getCliType() == target.getCliType()) {
                boolean shouldActivate = target.getCliType() != CliType.OPENCODE && p.getId().equals(providerId);
                p.setActive(shouldActivate);
                p.setPendingActivation(false);
            }
        }

        saveProviders(providers);
        writeToLiveConfig(target);
        lastCodexActivationResult = switchCodexAuthStateIfNeeded(target);
        lastGrokActivationResult = switchGrokAuthStateIfNeeded(target);
        lastPiActivationResult = switchPiAuthStateIfNeeded(target);
        lastAntigravityActivationResult = switchAntigravityAuthStateIfNeeded(target);
    }

    public Set<String> getOpenCodeLiveProviderNames() {
        JsonObject existing = ConfigFileService.getInstance()
                .readJsonFile(ConfigFileService.getInstance().getProviderConfigPath(CliType.OPENCODE));
        if (!existing.has("provider") || !existing.get("provider").isJsonObject()) {
            return Set.of();
        }
        return new HashSet<>(existing.getAsJsonObject("provider").keySet());
    }

    public boolean removeOpenCodeLiveProvider(String providerId) throws IOException {
        Provider target = getProviders().stream()
                .filter(provider -> provider.getCliType() == CliType.OPENCODE)
                .filter(provider -> Objects.equals(provider.getId(), providerId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("OpenCode provider not found: " + providerId));

        boolean removed = removeOpenCodeLiveProviderByName(target.getName());
        if (removed) {
            fireChanged();
        }
        return removed;
    }

    public CodexActivationResult getLastCodexActivationResult() {
        return lastCodexActivationResult;
    }

    public GrokActivationResult getLastGrokActivationResult() {
        return lastGrokActivationResult;
    }

    public PiActivationResult getLastPiActivationResult() {
        return lastPiActivationResult;
    }

    public AntigravityAuthSnapshotService.RestoreResult getLastAntigravityActivationResult() {
        return lastAntigravityActivationResult;
    }

    public AntigravityAuthSnapshotService.RestoreResult prepareActiveAntigravityForLaunch() throws IOException {
        Optional<Provider> active = getActiveProvider(CliType.ANTIGRAVITY);
        if (active.isEmpty() || active.get().getAuthMode() != AuthMode.OFFICIAL_LOGIN) {
            lastAntigravityActivationResult = null;
            return null;
        }
        lastAntigravityActivationResult = switchAntigravityAuthStateIfNeeded(active.get());
        return lastAntigravityActivationResult;
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
            case ANTIGRAVITY -> writeAntigravityLive(svc, config);
            case CODEX -> {
                if (provider.getAuthMode() == AuthMode.OFFICIAL_LOGIN) {
                    writeCodexOfficialLive(svc, config);
                } else {
                    writeCodexLive(svc, config);
                }
            }
            case OPENCODE -> writeOpenCodeLive(svc, config, provider.getName());
            case GROK -> {
                if (provider.getAuthMode() == AuthMode.OFFICIAL_LOGIN) {
                    writeGrokOfficialLive(svc, config);
                } else {
                    writeGrokLive(svc, config);
                }
            }
            case PI -> {
                if (provider.getAuthMode() == AuthMode.OFFICIAL_LOGIN) {
                    writePiOfficialLive(svc);
                } else {
                    writePiLive(svc, config);
                }
            }
        }
    }

    /**
     * Antigravity: 将 settingsConfig.env 合并写入 ~/.gemini/antigravity-cli/settings.json
     */
    private void writeAntigravityLive(ConfigFileService svc, JsonObject config) throws IOException {
        Path path = svc.getProviderConfigPath(CliType.ANTIGRAVITY);
        JsonObject existing = svc.readJsonFile(path);

        if (config.has("env")) {
            JsonObject newEnv = config.getAsJsonObject("env");
            if (newEnv.keySet().isEmpty()) {
                existing.remove("env");
            } else {
                JsonObject env = existing.has("env") ? existing.getAsJsonObject("env") : new JsonObject();

                env.remove("GEMINI_API_KEY");
                env.remove("GEMINI_BASE_URL");
                env.remove("GEMINI_MODEL");

                for (String key : newEnv.keySet()) {
                    env.add(key, newEnv.get(key));
                }
                existing.add("env", env);
            }
        }

        svc.writeJsonFile(path, existing);
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
            env.remove("CLAUDE_CODE_AUTO_COMPACT_WINDOW");
            env.remove("CLAUDE_CODE_EFFORT_LEVEL");
            env.remove("CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS");
            env.remove("ENABLE_TOOL_SEARCH");
            env.remove("DISABLE_AUTOUPDATER");
            env.remove("CLAUDE_CODE_NO_FLICKER");
            env.remove("CLAUDE_CODE_DISABLE_MOUSE");

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
            svc.writeCodexAuthJson(config.getAsJsonObject("auth"));
        } else {
            svc.deleteCodexAuthFile();
        }

        // 写 config.toml
        if (config.has("config")) {
            Path tomlPath = svc.getConfigDir(CliType.CODEX).resolve("config.toml");
            String providerToml = prepareCodexModelCatalog(svc, config);
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
        String providerToml = prepareCodexModelCatalog(svc, config);
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

    private String prepareCodexModelCatalog(ConfigFileService svc, JsonObject config) throws IOException {
        if (config == null || !config.has("config") || config.get("config").isJsonNull()) {
            return "";
        }

        String providerToml = config.get("config").getAsString().trim();
        if (!config.has(CodexModelCatalogSupport.SETTINGS_KEY)
                || !config.get(CodexModelCatalogSupport.SETTINGS_KEY).isJsonObject()) {
            return providerToml;
        }

        JsonObject catalog = config.getAsJsonObject(CodexModelCatalogSupport.SETTINGS_KEY);
        svc.writeJsonFile(svc.getCodexModelCatalogPath(), catalog);
        return CodexModelCatalogSupport.ensureCatalogPath(providerToml, svc.getCodexModelCatalogPath()).trim();
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

    private boolean removeOpenCodeLiveProviderByName(String name) throws IOException {
        if (name == null || name.isBlank()) {
            return false;
        }

        ConfigFileService svc = ConfigFileService.getInstance();
        Path path = svc.getProviderConfigPath(CliType.OPENCODE);
        JsonObject existing = svc.readJsonFile(path);
        if (!existing.has("provider") || !existing.get("provider").isJsonObject()) {
            return false;
        }

        JsonObject provider = existing.getAsJsonObject("provider");
        if (!provider.has(name)) {
            return false;
        }
        provider.remove(name);
        if (provider.keySet().isEmpty()) {
            existing.remove("provider");
        } else {
            existing.add("provider", provider);
        }

        svc.writeJsonFile(path, existing);
        return true;
    }

    // =====================================================================
    // 内部工具
    // =====================================================================

    private void saveProviders(List<Provider> providers) {
        providers.forEach(this::normalizeProvider);
        State nextState = new State();
        nextState.providersJson = GSON.toJson(providers);
        saveActiveState(nextState);
        fireChanged();
    }

    public State snapshotCurrentState() {
        State snapshot = new State();
        snapshot.providersJson = GSON.toJson(getProviders());
        return normalizeState(snapshot);
    }

    public State snapshotLocalState() {
        return normalizeState(myState);
    }

    public State snapshotSharedState() {
        return readSharedState(new State());
    }

    public void overwriteLocalState(State state) {
        myState = normalizeState(state);
    }

    public void writeSharedState(State state) {
        PluginDataStorage.writeJsonText(PluginDataStorage.getSharedProvidersPath(), normalizeState(state).providersJson);
    }

    public void notifyStateChanged() {
        fireChanged();
    }

    private State getActiveState() {
        if (PluginSettings.getInstance().getStorageMode() == PluginSettings.DataStorageMode.USER_SHARED) {
            return readSharedState(normalizeState(myState));
        }
        return myState;
    }

    private void saveActiveState(State state) {
        State normalized = normalizeState(state);
        if (PluginSettings.getInstance().getStorageMode() == PluginSettings.DataStorageMode.USER_SHARED) {
            writeSharedState(normalized);
        } else {
            myState = normalized;
        }
    }

    private State readSharedState(State defaultState) {
        State shared = new State();
        shared.providersJson = PluginDataStorage.readJsonText(
                PluginDataStorage.getSharedProvidersPath(),
                normalizeState(defaultState).providersJson);
        return normalizeState(shared);
    }

    private static State normalizeState(State state) {
        State normalized = state == null ? new State() : state;
        if (normalized.providersJson == null || normalized.providersJson.isBlank()) {
            normalized.providersJson = "[]";
        }
        return normalized;
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
        if (provider == null) {
            return;
        }
        if (provider.getCliType() == CliType.ANTIGRAVITY) {
            provider.setAuthMode(Provider.AuthMode.OFFICIAL_LOGIN);
        } else if (provider.getStoredAuthMode() == null) {
            provider.setAuthMode(Provider.inferAuthMode(provider.getCliType(), provider.getSettingsConfig()));
        }
        if (provider.getCliType() == CliType.OPENCODE) {
            provider.setActive(false);
            provider.setPendingActivation(false);
        }
        if (provider.getAuthBindingKey() == null || provider.getAuthBindingKey().isBlank()) {
            provider.setAuthBindingKey(provider.getId());
        }
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


    private Provider findActiveProvider(List<Provider> providers, CliType cliType) {
        if (providers == null || cliType == null) {
            return null;
        }
        return providers.stream()
                .filter(Provider::isActive)
                .filter(p -> p.getCliType() == cliType)
                .findFirst()
                .orElse(null);
    }

    /**
     * Grok: 将自定义模型写入 ~/.grok/config.toml 托管块。
     * api_key 放在 [model.*] 内，不覆盖官方 login 的 auth.json。
     */
    private void writeGrokLive(ConfigFileService svc, JsonObject config) throws IOException {
        Path tomlPath = svc.getGrokConfigTomlPath();
        String providerToml = config != null && config.has("config") && !config.get("config").isJsonNull()
                ? config.get("config").getAsString().trim()
                : "";
        String managedBlock = "# >>> coding-switch:provider:start\n"
                + providerToml + "\n"
                + "# <<< coding-switch:provider:end\n";
        String existing = svc.readFile(tomlPath);
        String withoutManagedBlock = removeManagedBlock(
                existing,
                "# >>> coding-switch:provider:start",
                "# <<< coding-switch:provider:end");
        String sanitized = removeConflictingGrokProviderEntries(withoutManagedBlock, providerToml);
        String merged = prependManagedBlock(sanitized, managedBlock);
        svc.writeFile(tomlPath, merged);
    }

    private void writeGrokOfficialLive(ConfigFileService svc, JsonObject config) throws IOException {
        Path tomlPath = svc.getGrokConfigTomlPath();
        boolean existed = Files.exists(tomlPath);
        String existing = svc.readFile(tomlPath);
        String withoutManagedBlock = removeManagedBlock(
                existing,
                "# >>> coding-switch:provider:start",
                "# <<< coding-switch:provider:end");
        String providerToml = config != null && config.has("config") && !config.get("config").isJsonNull()
                ? config.get("config").getAsString().trim()
                : "";
        String sanitized = removeConflictingGrokProviderEntries(withoutManagedBlock, providerToml);
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
     * Pi: 内置目录合并 auth.json + settings.json；自定义接口额外写入 models.json。
     * 不覆盖其他 provider 的 OAuth / API Key。
     */
    private void writePiLive(ConfigFileService svc, JsonObject config) throws IOException {
        PiConfigSupport.ParsedConfig parsed = PiConfigSupport.parse(config);
        mergePiAuth(svc, config);
        mergePiModels(svc, parsed);
        mergePiSettings(svc, parsed, false);
    }

    private void writePiOfficialLive(ConfigFileService svc) throws IOException {
        removePiManagedModels(svc);
        mergePiSettings(svc, null, true);
    }

    private void mergePiAuth(ConfigFileService svc, JsonObject config) throws IOException {
        JsonObject live = svc.readPiAuthJson();
        live.remove(PiConfigSupport.CUSTOM_PROVIDER_ID);
        JsonObject incoming = config != null && config.has("auth") && config.get("auth").isJsonObject()
                ? config.getAsJsonObject("auth")
                : new JsonObject();
        for (String key : incoming.keySet()) {
            live.add(key, incoming.get(key));
        }
        svc.writePiAuthJson(live);
    }

    private void mergePiModels(ConfigFileService svc, PiConfigSupport.ParsedConfig parsed) throws IOException {
        JsonObject live = svc.readPiModelsJson();
        JsonObject providers = live.has("providers") && live.get("providers").isJsonObject()
                ? live.getAsJsonObject("providers")
                : new JsonObject();
        providers.remove(PiConfigSupport.CUSTOM_PROVIDER_ID);
        if (parsed != null && parsed.custom()) {
            JsonObject models = PiConfigSupport.buildModels(
                    parsed.providerId(),
                    parsed.baseUrl(),
                    parsed.api(),
                    parsed.apiKey(),
                    parsed.model());
            JsonObject incomingProviders = models.has("providers") && models.get("providers").isJsonObject()
                    ? models.getAsJsonObject("providers")
                    : new JsonObject();
            for (String key : incomingProviders.keySet()) {
                providers.add(key, incomingProviders.get(key));
            }
        }
        live.add("providers", providers);
        svc.writePiModelsJson(live);
    }

    private void removePiManagedModels(ConfigFileService svc) throws IOException {
        JsonObject live = svc.readPiModelsJson();
        if (!live.has("providers") || !live.get("providers").isJsonObject()) {
            return;
        }
        JsonObject providers = live.getAsJsonObject("providers");
        if (!providers.has(PiConfigSupport.CUSTOM_PROVIDER_ID)) {
            return;
        }
        providers.remove(PiConfigSupport.CUSTOM_PROVIDER_ID);
        live.add("providers", providers);
        svc.writePiModelsJson(live);
    }

    private void mergePiSettings(
            ConfigFileService svc,
            PiConfigSupport.ParsedConfig parsed,
            boolean officialLogin
    ) throws IOException {
        JsonObject live = svc.readPiSettingsJson();
        if (officialLogin) {
            live.remove("defaultProvider");
            live.remove("defaultModel");
            svc.writePiSettingsJson(live);
            return;
        }
        if (parsed == null) {
            return;
        }
        live.addProperty("defaultProvider", parsed.providerId());
        if (parsed.model() != null && !parsed.model().isBlank()) {
            live.addProperty("defaultModel", parsed.model());
        } else {
            live.remove("defaultModel");
        }
        svc.writePiSettingsJson(live);
    }

    private void captureCurrentPiSnapshot(Provider target, Provider activePi) {
        if (target == null || target.getCliType() != CliType.PI) {
            return;
        }
        if (activePi == null || activePi.getAuthMode() != AuthMode.OFFICIAL_LOGIN) {
            return;
        }
        PiAuthSnapshotService.getInstance().captureFromLive(activePi);
    }

    private PiActivationResult switchPiAuthStateIfNeeded(Provider target) throws IOException {
        if (target.getCliType() != CliType.PI || target.getAuthMode() != AuthMode.OFFICIAL_LOGIN) {
            return PiActivationResult.notApplicable();
        }
        PiAuthSnapshotService.RestoreResult restoreResult = PiAuthSnapshotService.getInstance()
                .restoreToLive(target);
        return switch (restoreResult) {
            case RESTORED -> PiActivationResult.snapshotRestored();
            case NO_SNAPSHOT -> PiActivationResult.loginRequired();
            case INVALID_SNAPSHOT -> PiActivationResult.snapshotInvalid();
        };
    }

    private void captureCurrentCodexSnapshot(Provider target, Provider activeCodex) {
        if (target == null || target.getCliType() != CliType.CODEX) {
            return;
        }
        if (activeCodex == null || activeCodex.getAuthMode() != AuthMode.OFFICIAL_LOGIN) {
            return;
        }
        CodexAuthSnapshotService.getInstance().captureFromLive(activeCodex);
    }

    private void captureCurrentGrokSnapshot(Provider target, Provider activeGrok) {
        if (target == null || target.getCliType() != CliType.GROK) {
            return;
        }
        if (activeGrok == null || activeGrok.getAuthMode() != AuthMode.OFFICIAL_LOGIN) {
            return;
        }
        GrokAuthSnapshotService.getInstance().captureFromLive(activeGrok);
    }

    private void captureCurrentAntigravitySnapshot(Provider target, Provider activeAntigravity) {
        if (target == null || target.getCliType() != CliType.ANTIGRAVITY) {
            return;
        }
        if (activeAntigravity == null || activeAntigravity.getAuthMode() != AuthMode.OFFICIAL_LOGIN) {
            return;
        }
        AntigravityAuthSnapshotService.getInstance().captureFromLive(activeAntigravity);
    }

    private CodexActivationResult switchCodexAuthStateIfNeeded(Provider target) throws IOException {
        if (target.getCliType() != CliType.CODEX || target.getAuthMode() != AuthMode.OFFICIAL_LOGIN) {
            return CodexActivationResult.notApplicable();
        }

        CodexAuthSnapshotService.RestoreResult restoreResult = CodexAuthSnapshotService.getInstance()
                .restoreToLive(target);
        return switch (restoreResult) {
            case RESTORED -> CodexActivationResult.snapshotRestored();
            case NO_SNAPSHOT -> CodexActivationResult.loginRequired();
            case INVALID_SNAPSHOT -> CodexActivationResult.snapshotInvalid();
        };
    }

    private GrokActivationResult switchGrokAuthStateIfNeeded(Provider target) throws IOException {
        if (target.getCliType() != CliType.GROK || target.getAuthMode() != AuthMode.OFFICIAL_LOGIN) {
            return GrokActivationResult.notApplicable();
        }

        GrokAuthSnapshotService.RestoreResult restoreResult = GrokAuthSnapshotService.getInstance()
                .restoreToLive(target);
        return switch (restoreResult) {
            case RESTORED -> GrokActivationResult.snapshotRestored();
            case NO_SNAPSHOT -> GrokActivationResult.loginRequired();
            case INVALID_SNAPSHOT -> GrokActivationResult.snapshotInvalid();
        };
    }

    private AntigravityAuthSnapshotService.RestoreResult switchAntigravityAuthStateIfNeeded(Provider target) throws IOException {
        if (target.getCliType() != CliType.ANTIGRAVITY || target.getAuthMode() != AuthMode.OFFICIAL_LOGIN) {
            return null;
        }
        return AntigravityAuthSnapshotService.getInstance().restoreToLive(target);
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
        Set<String> managedRootKeys = new HashSet<>(Set.of(
                "model_provider",
                "model",
                "model_reasoning_effort",
                "model_context_window",
                "model_auto_compact_token_limit",
                "multi_agent",
                "service_tier",
                "fast_mode",
                "disable_response_storage",
                "approval_policy",
                "sandbox_mode"));
        managedRootKeys.addAll(extractRootTomlKeys(managedProviderToml));

        StringBuilder out = new StringBuilder();
        String currentSection = null;
        boolean dropCurrentSection = false;
        // 废弃的 section 列表（需要移除整个 section）
        Set<String> deprecatedSections = Set.of("features.collab", "features.collab.");
        // section 内部需要移除的废弃键
        Map<String, Set<String>> deprecatedKeysInSection = Map.of(
                "features", Set.of("collab")
        );

        for (String rawLine : safe.split("\n", -1)) {
            String line = rawLine.trim();

            if (line.startsWith("[") && line.endsWith("]")) {
                currentSection = line.substring(1, line.length() - 1).trim();
                dropCurrentSection = isManagedProviderSection(currentSection, managedProviderNames)
                        || isDeprecatedSection(currentSection, deprecatedSections);
            }

            if (dropCurrentSection) {
                continue;
            }

            String key = parseTomlKey(rawLine);
            // 移除根级别的托管键
            if (currentSection == null && key != null && managedRootKeys.contains(key)) {
                continue;
            }
            // 移除 section 内部的废弃键
            if (currentSection != null && key != null
                    && deprecatedKeysInSection.containsKey(currentSection)
                    && deprecatedKeysInSection.get(currentSection).contains(key)) {
                continue;
            }

            out.append(rawLine).append("\n");
        }
        return out.toString();
    }

    private static String removeConflictingGrokProviderEntries(String content, String managedProviderToml) {
        String safe = content == null ? "" : content;
        if (safe.isBlank()) {
            return safe;
        }

        Set<String> managedModelKeys = GrokConfigSupport.extractManagedModelKeys(managedProviderToml);
        boolean manageDefaultModel = GrokConfigSupport.hasCustomModelConfig(managedProviderToml);
        StringBuilder out = new StringBuilder();
        String currentSection = null;
        boolean dropCurrentSection = false;

        for (String rawLine : safe.split("\n", -1)) {
            String line = rawLine.trim();
            if (line.startsWith("[") && line.endsWith("]")) {
                currentSection = line.substring(1, line.length() - 1).trim();
                String unquoted = currentSection.replace("\"", "").replace("'", "");
                dropCurrentSection = isManagedGrokModelSection(unquoted, managedModelKeys);
            }
            if (dropCurrentSection) {
                continue;
            }
            String unquotedSection = currentSection == null ? null : currentSection.replace("\"", "").replace("'", "");
            if (manageDefaultModel && "models".equals(unquotedSection)) {
                String key = parseTomlKey(rawLine);
                if ("default".equals(key)) {
                    continue;
                }
            }
            out.append(rawLine).append("\n");
        }

        return out.toString();
    }

    private static boolean isManagedGrokModelSection(String section, Set<String> managedModelKeys) {
        if (section == null || !section.startsWith("model.")) {
            return false;
        }
        String suffix = section.substring("model.".length()).trim();
        if (managedModelKeys == null || managedModelKeys.isEmpty()) {
            return false;
        }
        return managedModelKeys.contains(suffix);
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

    private static Set<String> extractRootTomlKeys(String toml) {
        Set<String> keys = new HashSet<>();
        if (toml == null || toml.isBlank()) {
            return keys;
        }
        String currentSection = null;
        for (String rawLine : toml.split("\n")) {
            String line = rawLine.trim();
            if (line.startsWith("[") && line.endsWith("]")) {
                currentSection = line.substring(1, line.length() - 1).trim();
                continue;
            }
            String key = parseTomlKey(rawLine);
            if (currentSection == null && key != null) {
                keys.add(key);
            }
        }
        return keys;
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

    private static boolean isDeprecatedSection(String section, Set<String> deprecatedSections) {
        if (deprecatedSections == null || deprecatedSections.isEmpty()) {
            return false;
        }
        for (String deprecated : deprecatedSections) {
            if (section.equals(deprecated) || section.startsWith(deprecated)) {
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
