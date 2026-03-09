package com.github.mostbean.codingswitch.service;

import com.github.mostbean.codingswitch.model.CliType;
import com.github.mostbean.codingswitch.model.Provider;
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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Provider management service.
 */
@Service(Service.Level.APP)
@State(name = "CodingSwitchProviders", storages = @Storage("coding-switch-providers.xml"))
public final class ProviderService implements PersistentStateComponent<ProviderService.State> {

    private static final Logger LOG = Logger.getInstance(ProviderService.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final DateTimeFormatter IMPORTED_OMO_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    public static class State {
        public String providersJson = "[]";
    }

    private State myState = new State();
    private final List<Runnable> changeListeners = new ArrayList<>();
    private boolean normalized;

    public static ProviderService getInstance() {
        return ApplicationManager.getApplication().getService(ProviderService.class);
    }

    @Override
    public @Nullable State getState() {
        ensureNormalized();
        myState.providersJson = GSON.toJson(parseProviders());
        return myState;
    }

    @Override
    public void loadState(@NotNull State state) {
        myState = state;
        normalized = false;
    }

    public List<Provider> getProviders() {
        ensureNormalized();
        List<Provider> providers = parseProviders();
        providers.sort(Comparator
                .comparing((Provider p) -> p.getCliType() != null ? p.getCliType().getDisplayName() : "",
                        String.CASE_INSENSITIVE_ORDER)
                .thenComparing((Provider p) -> p.getCreatedAt() != null ? p.getCreatedAt() : 0L)
                .thenComparing(Provider::getId, Comparator.nullsLast(String::compareTo)));
        return providers;
    }

    public List<Provider> getProvidersByType(CliType cliType) {
        return getProviders().stream()
                .filter(p -> p.getCliType() == cliType)
                .toList();
    }

    public List<Provider> getOpenCodeCustomProviders() {
        return getProvidersByType(CliType.OPENCODE).stream()
                .filter(Provider::isOpenCodeCustomCategory)
                .toList();
    }

    public Optional<Provider> getActiveProvider(CliType cliType) {
        return getProviders().stream()
                .filter(p -> p.getCliType() == cliType && p.isActive())
                .findFirst();
    }

    public Optional<Provider> getCurrentOpenCodeOmoProvider(String category) {
        return getProvidersByType(CliType.OPENCODE).stream()
                .filter(provider -> provider.isOmoCategory() && Objects.equals(provider.getNormalizedCategory(), category))
                .filter(Provider::isActive)
                .findFirst();
    }

    public Set<String> getExistingOpenCodeProviderKeys(@Nullable String excludeProviderId) {
        Set<String> keys = new LinkedHashSet<>();
        for (Provider provider : getOpenCodeCustomProviders()) {
            if (excludeProviderId != null && excludeProviderId.equals(provider.getId())) {
                continue;
            }
            if (provider.getProviderKey() != null && !provider.getProviderKey().isBlank()) {
                keys.add(provider.getProviderKey().trim());
            }
        }
        return keys;
    }

    public boolean isOpenCodeProviderKeyAvailable(String providerKey, @Nullable String excludeProviderId) {
        if (providerKey == null || providerKey.isBlank()) {
            return false;
        }
        return !getExistingOpenCodeProviderKeys(excludeProviderId).contains(providerKey.trim());
    }

    public String suggestOpenCodeProviderKey(String name, @Nullable String excludeProviderId) {
        Set<String> usedKeys = getExistingOpenCodeProviderKeys(excludeProviderId);
        return buildUniqueProviderKey(name, usedKeys);
    }

    public boolean isOpenCodeProviderSynced(Provider provider) {
        return OpenCodeConfigService.getInstance().isProviderSynced(provider);
    }

    public boolean isOpenCodeOmoApplied(Provider provider) {
        if (provider == null || !provider.isOmoCategory()) {
            return false;
        }
        OpenCodeConfigService service = OpenCodeConfigService.getInstance();
        if (!provider.isActive() || !service.isOmoApplied(provider.getNormalizedCategory())) {
            return false;
        }
        try {
            OpenCodeConfigService.OmoLocalFileData localFileData = service.readOmoLocalFile(provider.getNormalizedCategory());
            JsonObject liveDraft = service.toOmoDraftSettings(localFileData, provider.getNormalizedCategory());
            return service.buildMergedOmoConfig(provider).equals(
                    service.buildMergedOmoConfig(provider.getNormalizedCategory(), liveDraft));
        } catch (Exception e) {
            LOG.warn("Failed to compare OMO live config state", e);
            return false;
        }
    }

    public void addProvider(Provider provider) throws IOException {
        ensureNormalized();
        List<Provider> providers = parseProviders();
        Provider prepared = prepareProviderForSave(provider, null, providers);

        if (prepared.isOpenCodeCustomCategory()) {
            OpenCodeConfigService.getInstance().syncProvider(prepared);
        }

        providers.add(prepared);
        saveProviders(providers);
    }

    public void updateProvider(Provider provider) throws IOException {
        ensureNormalized();
        List<Provider> providers = parseProviders();
        Provider existing = providers.stream()
                .filter(p -> Objects.equals(p.getId(), provider.getId()))
                .findFirst()
                .orElse(null);
        if (existing == null) {
            throw new IllegalArgumentException("Provider not found: " + provider.getId());
        }

        Provider prepared = prepareProviderForSave(provider, existing, providers);
        OpenCodeConfigService openCodeConfigService = OpenCodeConfigService.getInstance();

        if (existing.isOpenCodeCustomCategory()
                && (!prepared.isOpenCodeCustomCategory()
                || !Objects.equals(existing.getProviderKey(), prepared.getProviderKey()))) {
            openCodeConfigService.removeProvider(existing.getProviderKey());
        }

        if (existing.isOmoCategory()
                && existing.isActive()
                && !Objects.equals(existing.getNormalizedCategory(), prepared.getNormalizedCategory())) {
            openCodeConfigService.disableOmo(existing.getNormalizedCategory());
            prepared.setActive(false);
        }

        if (prepared.isOpenCodeCustomCategory()) {
            openCodeConfigService.syncProvider(prepared);
        }

        providers.replaceAll(p -> Objects.equals(p.getId(), prepared.getId()) ? prepared : p);
        saveProviders(providers);
    }

    public void removeProvider(String providerId) throws IOException {
        ensureNormalized();
        List<Provider> providers = parseProviders();
        Provider existing = providers.stream()
                .filter(p -> Objects.equals(p.getId(), providerId))
                .findFirst()
                .orElse(null);
        if (existing == null) {
            return;
        }

        if (existing.isOpenCodeCustomCategory()) {
            OpenCodeConfigService.getInstance().removeProvider(existing.getProviderKey());
        } else if (existing.isOmoCategory() && existing.isActive()) {
            OpenCodeConfigService.getInstance().disableOmo(existing.getNormalizedCategory());
        }

        providers.removeIf(p -> Objects.equals(p.getId(), providerId));
        saveProviders(providers);
    }

    public void duplicateProvider(String providerId) throws IOException {
        Provider source = getProviders().stream()
                .filter(p -> Objects.equals(p.getId(), providerId))
                .findFirst()
                .orElse(null);
        if (source != null) {
            addProvider(source.copy());
        }
    }

    public void activateProvider(String providerId) throws IOException {
        ensureNormalized();
        List<Provider> providers = parseProviders();
        Provider target = providers.stream()
                .filter(p -> Objects.equals(p.getId(), providerId))
                .findFirst()
                .orElse(null);
        if (target == null) {
            throw new IllegalArgumentException("Provider not found: " + providerId);
        }

        if (target.getCliType() == CliType.OPENCODE) {
            activateOpenCodeProvider(target, providers);
            saveProviders(providers);
            return;
        }

        for (Provider provider : providers) {
            if (provider.getCliType() == target.getCliType()) {
                boolean shouldActivate = Objects.equals(provider.getId(), target.getId());
                provider.setActive(shouldActivate);
                provider.setPendingActivation(false);
            }
        }

        saveProviders(providers);
        writeToLiveConfig(target);
    }

    public void deactivateProvider(String providerId) throws IOException {
        ensureNormalized();
        List<Provider> providers = parseProviders();
        Provider target = providers.stream()
                .filter(p -> Objects.equals(p.getId(), providerId))
                .findFirst()
                .orElse(null);
        if (target == null) {
            throw new IllegalArgumentException("Provider not found: " + providerId);
        }
        if (target.getCliType() != CliType.OPENCODE) {
            return;
        }

        OpenCodeConfigService openCodeConfigService = OpenCodeConfigService.getInstance();
        if (target.isOpenCodeCustomCategory()) {
            openCodeConfigService.removeProvider(target.getProviderKey());
        } else if (target.isOmoCategory()) {
            openCodeConfigService.disableOmo(target.getNormalizedCategory());
            for (Provider provider : providers) {
                if (provider.isOmoCategory() && Objects.equals(provider.getNormalizedCategory(), target.getNormalizedCategory())) {
                    provider.setActive(false);
                }
            }
        }
        saveProviders(providers);
    }

    public void addChangeListener(Runnable listener) {
        changeListeners.add(listener);
    }

    private void ensureNormalized() {
        if (normalized) {
            return;
        }
        synchronized (this) {
            if (normalized) {
                return;
            }
            List<Provider> providers = parseProviders();
            boolean changed = normalizeProviders(providers);
            if (changed) {
                saveProvidersInternal(providers, false);
            } else {
                myState.providersJson = GSON.toJson(providers);
            }
            normalized = true;
        }
    }

    private List<Provider> parseProviders() {
        try {
            List<Provider> list = GSON.fromJson(myState.providersJson,
                    new TypeToken<List<Provider>>() {
                    }.getType());
            return list != null ? new ArrayList<>(list) : new ArrayList<>();
        } catch (Exception e) {
            LOG.warn("Failed to parse providers", e);
            return new ArrayList<>();
        }
    }

    private boolean normalizeProviders(List<Provider> providers) {
        boolean changed = false;
        Set<String> usedKeys = new LinkedHashSet<>();

        for (Provider provider : providers) {
            changed |= normalizeProviderDefaults(provider);
            if (provider.isOpenCodeCustomCategory()) {
                String normalizedKey = normalizeExistingProviderKey(provider.getProviderKey(), provider.getName(), usedKeys);
                if (!Objects.equals(normalizedKey, provider.getProviderKey())) {
                    provider.setProviderKey(normalizedKey);
                    changed = true;
                }
                usedKeys.add(normalizedKey);
                if (provider.isActive() || provider.isPendingActivation()) {
                    provider.setActive(false);
                    provider.setPendingActivation(false);
                    changed = true;
                }
            } else if (provider.isOmoCategory()) {
                if (provider.getProviderKey() != null) {
                    provider.setProviderKey(null);
                    changed = true;
                }
                if (provider.isPendingActivation()) {
                    provider.setPendingActivation(false);
                    changed = true;
                }
            }
        }

        changed |= migrateLegacyOpenCodeLiveKeys(providers);
        changed |= importLegacyOmoProviders(providers);
        changed |= normalizeOmoActiveFlags(providers);
        return changed;
    }

    private boolean normalizeProviderDefaults(Provider provider) {
        boolean changed = false;
        if (provider.getId() == null || provider.getId().isBlank()) {
            provider.setId(UUID.randomUUID().toString());
            changed = true;
        }
        if (provider.getSettingsConfig() == null) {
            provider.setSettingsConfig(new JsonObject());
            changed = true;
        }
        if (provider.getCreatedAt() == null) {
            provider.setCreatedAt(System.currentTimeMillis());
            changed = true;
        }
        if (provider.getCliType() == CliType.OPENCODE) {
            String normalizedCategory = provider.getNormalizedCategory();
            if (!Objects.equals(normalizedCategory, provider.getCategory())) {
                provider.setCategory(normalizedCategory);
                changed = true;
            }
        } else if (provider.getCategory() != null || provider.getProviderKey() != null) {
            provider.setCategory(null);
            provider.setProviderKey(null);
            changed = true;
        }
        return changed;
    }

    private String normalizeExistingProviderKey(String currentKey, String providerName, Set<String> usedKeys) {
        if (currentKey != null && !currentKey.isBlank()) {
            String trimmed = currentKey.trim();
            if (!usedKeys.contains(trimmed)) {
                return trimmed;
            }
        }
        return buildUniqueProviderKey(providerName, usedKeys);
    }

    private boolean migrateLegacyOpenCodeLiveKeys(List<Provider> providers) {
        OpenCodeConfigService openCodeConfigService = OpenCodeConfigService.getInstance();
        JsonObject liveConfig = openCodeConfigService.readLiveConfig();
        JsonObject liveProviders = liveConfig.has("provider") && liveConfig.get("provider").isJsonObject()
                ? liveConfig.getAsJsonObject("provider")
                : new JsonObject();

        boolean changed = false;
        for (Provider provider : providers) {
            if (!provider.isOpenCodeCustomCategory() || provider.getProviderKey() == null) {
                continue;
            }
            String legacyKey = provider.getName();
            String providerKey = provider.getProviderKey();
            if (legacyKey == null || legacyKey.isBlank() || Objects.equals(legacyKey, providerKey)) {
                continue;
            }
            if (liveProviders.has(legacyKey)) {
                if (!liveProviders.has(providerKey)) {
                    liveProviders.add(providerKey, liveProviders.get(legacyKey).deepCopy());
                }
                liveProviders.remove(legacyKey);
                changed = true;
            }
        }

        if (changed) {
            try {
                if (liveProviders.keySet().isEmpty()) {
                    liveConfig.remove("provider");
                } else {
                    liveConfig.add("provider", liveProviders);
                }
                openCodeConfigService.writeLiveConfig(liveConfig);
            } catch (IOException e) {
                LOG.warn("Failed to migrate legacy OpenCode live provider keys", e);
            }
        }
        return changed;
    }

    private boolean importLegacyOmoProviders(List<Provider> providers) {
        boolean changed = false;
        changed |= importLegacyOmoProviderIfNeeded(providers, Provider.CATEGORY_OMO, "Oh My OpenCode");
        changed |= importLegacyOmoProviderIfNeeded(providers, Provider.CATEGORY_OMO_SLIM, "Oh My OpenCode Slim");
        return changed;
    }

    private boolean importLegacyOmoProviderIfNeeded(List<Provider> providers, String category, String displayName) {
        boolean alreadyExists = providers.stream()
                .anyMatch(provider -> provider.getCliType() == CliType.OPENCODE
                        && Objects.equals(provider.getNormalizedCategory(), category));
        if (alreadyExists) {
            return false;
        }

        OpenCodeConfigService openCodeConfigService = OpenCodeConfigService.getInstance();
        Path localPath = openCodeConfigService.resolveOmoLocalFilePath(category);
        if (localPath == null || !Files.exists(localPath) || !openCodeConfigService.isOmoApplied(category)) {
            return false;
        }

        try {
            OpenCodeConfigService.OmoLocalFileData localFileData = openCodeConfigService.readOmoLocalFile(localPath, category);
            Provider imported = new Provider(CliType.OPENCODE,
                    displayName + " " + IMPORTED_OMO_TIME.format(LocalDateTime.now()));
            imported.setCategory(category);
            imported.setSettingsConfig(openCodeConfigService.toOmoDraftSettings(localFileData, category));
            imported.setActive(true);
            imported.setPendingActivation(false);
            providers.add(imported);
            return true;
        } catch (Exception e) {
            LOG.warn("Failed to import legacy OMO provider from local file", e);
            return false;
        }
    }

    private boolean normalizeOmoActiveFlags(List<Provider> providers) {
        boolean changed = false;
        String appliedCategory = detectAppliedOmoCategory();

        for (String category : List.of(Provider.CATEGORY_OMO, Provider.CATEGORY_OMO_SLIM)) {
            List<Provider> sameCategory = providers.stream()
                    .filter(provider -> provider.isOmoCategory()
                            && Objects.equals(provider.getNormalizedCategory(), category))
                    .sorted(Comparator.comparing((Provider p) -> p.getCreatedAt() != null ? p.getCreatedAt() : 0L).reversed())
                    .toList();

            Provider selected = null;
            if (Objects.equals(appliedCategory, category)) {
                selected = sameCategory.stream().filter(Provider::isActive).findFirst()
                        .orElse(sameCategory.isEmpty() ? null : sameCategory.get(0));
            }

            for (Provider provider : sameCategory) {
                boolean shouldBeActive = selected != null && Objects.equals(provider.getId(), selected.getId());
                if (provider.isActive() != shouldBeActive) {
                    provider.setActive(shouldBeActive);
                    changed = true;
                }
                if (provider.isPendingActivation()) {
                    provider.setPendingActivation(false);
                    changed = true;
                }
            }
        }
        return changed;
    }

    private String detectAppliedOmoCategory() {
        OpenCodeConfigService openCodeConfigService = OpenCodeConfigService.getInstance();
        if (openCodeConfigService.isOmoApplied(Provider.CATEGORY_OMO_SLIM)) {
            return Provider.CATEGORY_OMO_SLIM;
        }
        if (openCodeConfigService.isOmoApplied(Provider.CATEGORY_OMO)) {
            return Provider.CATEGORY_OMO;
        }
        return null;
    }

    private Provider prepareProviderForSave(Provider candidate, @Nullable Provider existing, List<Provider> allProviders) {
        Provider prepared = candidate;
        if (prepared.getSettingsConfig() == null) {
            prepared.setSettingsConfig(new JsonObject());
        }
        if (prepared.getCreatedAt() == null) {
            prepared.setCreatedAt(existing != null && existing.getCreatedAt() != null
                    ? existing.getCreatedAt()
                    : System.currentTimeMillis());
        }

        if (prepared.getCliType() == CliType.OPENCODE) {
            prepared.setCategory(prepared.getNormalizedCategory());
            if (prepared.isOpenCodeCustomCategory()) {
                Set<String> usedKeys = new LinkedHashSet<>();
                for (Provider provider : allProviders) {
                    if (!provider.isOpenCodeCustomCategory()) {
                        continue;
                    }
                    if (existing != null && Objects.equals(provider.getId(), existing.getId())) {
                        continue;
                    }
                    if (provider.getProviderKey() != null && !provider.getProviderKey().isBlank()) {
                        usedKeys.add(provider.getProviderKey().trim());
                    }
                }

                String providerKey = prepared.getProviderKey();
                if (providerKey == null || providerKey.isBlank()) {
                    providerKey = buildUniqueProviderKey(prepared.getName(), usedKeys);
                }
                prepared.setProviderKey(providerKey.trim());
                prepared.setActive(false);
                prepared.setPendingActivation(false);
            } else {
                prepared.setProviderKey(null);
                prepared.setPendingActivation(false);
                prepared.setActive(existing != null
                        && existing.isOmoCategory()
                        && Objects.equals(existing.getNormalizedCategory(), prepared.getNormalizedCategory())
                        && existing.isActive());
            }
            return prepared;
        }

        prepared.setCategory(null);
        prepared.setProviderKey(null);

        if (existing != null) {
            prepared.setActive(existing.isActive());
            if (existing.isActive()) {
                boolean syncRelevantChanged = !Objects.equals(existing.getCliType(), prepared.getCliType())
                        || !Objects.equals(existing.getName(), prepared.getName())
                        || !Objects.equals(existing.getSettingsConfig(), prepared.getSettingsConfig());
                prepared.setPendingActivation(syncRelevantChanged || existing.isPendingActivation());
            } else {
                prepared.setPendingActivation(false);
            }
        } else {
            prepared.setActive(false);
            prepared.setPendingActivation(false);
        }
        return prepared;
    }

    private String buildUniqueProviderKey(String name, Set<String> usedKeys) {
        String base = slugifyProviderKey(name);
        if (base == null || base.isBlank()) {
            base = "opencode";
        }

        String candidate = base;
        int suffix = 2;
        while (usedKeys.contains(candidate)) {
            candidate = base + "-" + suffix;
            suffix++;
        }

        if (candidate.isBlank()) {
            candidate = "opencode-" + UUID.randomUUID().toString().substring(0, 8);
        }
        if (usedKeys.contains(candidate)) {
            candidate = "opencode-" + UUID.randomUUID().toString().substring(0, 8);
        }
        return candidate;
    }

    public static String slugifyProviderKey(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+", "")
                .replaceAll("-+$", "");
        return normalized.isBlank() ? null : normalized;
    }

    private void activateOpenCodeProvider(Provider target, List<Provider> providers) throws IOException {
        OpenCodeConfigService openCodeConfigService = OpenCodeConfigService.getInstance();
        if (target.isOpenCodeCustomCategory()) {
            openCodeConfigService.syncProvider(target);
            for (Provider provider : providers) {
                if (provider.isOpenCodeCustomCategory()) {
                    provider.setActive(false);
                    provider.setPendingActivation(false);
                }
            }
            return;
        }

        openCodeConfigService.applyOmoProvider(target);
        for (Provider provider : providers) {
            if (provider.isOmoCategory()) {
                provider.setActive(Objects.equals(provider.getId(), target.getId()));
                provider.setPendingActivation(false);
            }
        }
    }

    private void saveProviders(List<Provider> providers) {
        saveProvidersInternal(providers, true);
    }

    private void saveProvidersInternal(List<Provider> providers, boolean fireChanged) {
        myState.providersJson = GSON.toJson(providers);
        normalized = true;
        if (fireChanged) {
            fireChanged();
        }
    }

    private void fireChanged() {
        for (Runnable listener : changeListeners) {
            listener.run();
        }
    }

    private void writeToLiveConfig(Provider provider) throws IOException {
        ConfigFileService svc = ConfigFileService.getInstance();
        CliType cliType = provider.getCliType();
        JsonObject config = provider.getSettingsConfig();

        switch (cliType) {
            case CLAUDE -> writeClaudeLive(svc, config);
            case CODEX -> writeCodexLive(svc, config);
            case GEMINI -> writeGeminiLive(svc, config);
            case OPENCODE -> throw new IOException("OpenCode live writes are handled by OpenCodeConfigService");
        }
    }

    private void writeClaudeLive(ConfigFileService svc, JsonObject config) throws IOException {
        Path path = svc.getProviderConfigPath(CliType.CLAUDE);
        JsonObject existing = svc.readJsonFile(path);

        if (config.has("env")) {
            JsonObject env = existing.has("env") ? existing.getAsJsonObject("env") : new JsonObject();
            JsonObject newEnv = config.getAsJsonObject("env");

            env.remove("ANTHROPIC_AUTH_TOKEN");
            env.remove("ANTHROPIC_API_KEY");
            env.remove("ANTHROPIC_BASE_URL");
            env.remove("ANTHROPIC_MODEL");
            env.remove("ANTHROPIC_DEFAULT_HAIKU_MODEL");
            env.remove("ANTHROPIC_DEFAULT_SONNET_MODEL");
            env.remove("ANTHROPIC_DEFAULT_OPUS_MODEL");
            env.remove("CLAUDE_CODE_EFFORT_LEVEL");

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

        if (config.has("skipDangerousModePermissionPrompt")
                && config.get("skipDangerousModePermissionPrompt").getAsBoolean()) {
            existing.addProperty("skipDangerousModePermissionPrompt", true);
        } else {
            existing.remove("skipDangerousModePermissionPrompt");
        }

        svc.writeJsonFile(path, existing);
    }

    private void writeCodexLive(ConfigFileService svc, JsonObject config) throws IOException {
        if (config.has("auth")) {
            Path authPath = svc.getProviderConfigPath(CliType.CODEX);
            svc.writeJsonFile(authPath, config.getAsJsonObject("auth"));
        }

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
        Set<String> names = new LinkedHashSet<>();
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
