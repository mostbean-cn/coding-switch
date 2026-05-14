package com.github.mostbean.codingswitch.service;

import com.github.mostbean.codingswitch.model.AiCompletionTriggerMode;
import com.github.mostbean.codingswitch.model.AiCompletionLengthLevel;
import com.github.mostbean.codingswitch.model.AiModelFormat;
import com.github.mostbean.codingswitch.model.AiModelProfile;
import com.github.mostbean.codingswitch.model.CompletionTimingConfig;
import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.CredentialAttributesKt;
import com.intellij.ide.passwordSafe.PasswordSafe;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * IDE 内 AI 功能设置。此服务只服务插件自身功能，不写入任何 CLI 配置。
 */
@Service(Service.Level.APP)
@State(name = "CodingSwitchAiFeatureSettings", storages = @Storage("codingSwitchAiFeatureSettings.xml"))
public final class AiFeatureSettings implements PersistentStateComponent<AiFeatureSettings.State> {

    private static final String CREDENTIAL_SERVICE_NAME = "CodingSwitchAiModel";
    public static final String MANUAL_COMPLETION_ACTION_ID = "CodingSwitch.TriggerAiCompletion";
    public static final String DEFAULT_MANUAL_SHORTCUT = "alt L";

    public static class State {
        public boolean codeCompletionEnabled = false;
        public boolean gitCommitMessageEnabled = false;
        public String gitCommitMessageLanguage = GitCommitMessageLanguage.CHINESE.name();
        public boolean autoCompletionEnabled = false;
        public int autoCompletionMaxTokens = 64;
        public int manualCompletionMaxTokens = 160;
        public String autoCompletionLengthLevel = AiCompletionLengthLevel.SINGLE_LINE.name();
        public String manualCompletionLengthLevel = AiCompletionLengthLevel.SHORT.name();
        public String activeCompletionProfileId = "";
        public String activeGitCommitProfileId = "";
        public String manualCompletionShortcut = DEFAULT_MANUAL_SHORTCUT;
        public CompletionTimingConfig timingConfig = new CompletionTimingConfig();
        public List<AiModelProfile> profiles = new ArrayList<>();
    }

    private State state = new State();

    public static AiFeatureSettings getInstance() {
        return ApplicationManager.getApplication().getService(AiFeatureSettings.class);
    }

    @Override
    public @NotNull State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State state) {
        this.state = normalize(state);
    }

    public State snapshot() {
        return copyState(getActiveState());
    }

    public void update(State next) {
        saveActiveState(copyState(next));
    }

    public boolean isCodeCompletionEnabled() {
        return getActiveState().codeCompletionEnabled;
    }

    public boolean isAutoCompletionEnabled() {
        return getActiveState().autoCompletionEnabled;
    }

    public boolean isGitCommitMessageEnabled() {
        return getActiveState().gitCommitMessageEnabled;
    }

    public GitCommitMessageLanguage getGitCommitMessageLanguage() {
        return parseGitCommitMessageLanguage(getActiveState().gitCommitMessageLanguage);
    }

    public AiCompletionLengthLevel getCompletionLengthLevel(AiCompletionTriggerMode mode) {
        return mode == AiCompletionTriggerMode.MANUAL
            ? parseLengthLevel(getActiveState().manualCompletionLengthLevel, AiCompletionLengthLevel.SHORT)
            : parseLengthLevel(getActiveState().autoCompletionLengthLevel, AiCompletionLengthLevel.SINGLE_LINE);
    }

    public AiModelProfile getActiveCompletionProfile() {
        return getActiveProfile(getActiveState().activeCompletionProfileId);
    }

    public AiModelProfile getActiveGitCommitProfile() {
        return getActiveProfile(getActiveState().activeGitCommitProfileId);
    }

    private AiModelProfile getActiveProfile(String profileId) {
        State active = getActiveState();
        String activeId = profileId == null ? "" : profileId;
        for (AiModelProfile profile : active.profiles) {
            if (Objects.equals(activeId, profile.getId())) {
                return profile.copy();
            }
        }
        return active.profiles.isEmpty() ? null : active.profiles.get(0).copy();
    }

    public CompletionTimingConfig getTimingConfig() {
        State active = getActiveState();
        return active.timingConfig != null ? active.timingConfig.copy() : new CompletionTimingConfig();
    }

    public String getApiKey(String profileId) {
        if (profileId == null || profileId.isBlank()) {
            return "";
        }
        for (AiModelProfile profile : getActiveState().profiles) {
            if (Objects.equals(profileId, profile.getId())) {
                if (profile.hasApiKeySetting()) {
                    return profile.getApiKey();
                }
                String legacyApiKey = getPasswordSafeApiKey(profileId);
                return legacyApiKey == null ? "" : legacyApiKey.trim();
            }
        }
        String legacyApiKey = getPasswordSafeApiKey(profileId);
        return legacyApiKey == null ? "" : legacyApiKey.trim();
    }

    public void setApiKey(String profileId, String apiKey) {
        if (profileId == null || profileId.isBlank() || apiKey == null) {
            return;
        }
        updateProfileApiKey(profileId, apiKey);
        clearPasswordSafeApiKey(profileId);
    }

    private String getPasswordSafeApiKey(String profileId) {
        return PasswordSafe.getInstance().getPassword(createCredentialAttributes(profileId));
    }

    private void clearPasswordSafeApiKey(String profileId) {
        PasswordSafe.getInstance().set(createCredentialAttributes(profileId), null);
    }

    public void clearApiKey(String profileId) {
        if (profileId == null || profileId.isBlank()) {
            return;
        }
        updateProfileApiKey(profileId, "");
        clearPasswordSafeApiKey(profileId);
    }

    private CredentialAttributes createCredentialAttributes(String profileId) {
        return new CredentialAttributes(
            CredentialAttributesKt.generateServiceName(CREDENTIAL_SERVICE_NAME, profileId)
        );
    }

    public State snapshotCurrentState() {
        return withLegacyApiKeys(copyState(getActiveState()));
    }

    public State snapshotLocalState() {
        return withLegacyApiKeys(copyState(state));
    }

    public State snapshotSharedState() {
        return readSharedState(new State());
    }

    public void overwriteLocalState(State next) {
        this.state = normalize(copyState(next));
    }

    public void writeSharedState(State next) {
        PluginDataStorage.writeJson(PluginDataStorage.getSharedAiFeaturesPath(), normalize(copyState(next)));
    }

    public void notifyStateChanged() {
        // 当前 IDE 设置页按需读取快照，无需额外通知。
    }

    public void backfillInlineApiKeysFromLegacyPasswordSafe(State targetState) {
        if (PluginSettings.getInstance().getStorageMode() != PluginSettings.DataStorageMode.USER_SHARED) {
            return;
        }
        State stateWithApiKeys = normalize(copyState(targetState));
        boolean changed = false;
        for (AiModelProfile profile : stateWithApiKeys.profiles) {
            String profileId = profile.getId();
            if (profileId == null || profileId.isBlank() || profile.hasApiKeySetting()) {
                continue;
            }
            String localApiKey = getPasswordSafeApiKey(profileId);
            if (localApiKey != null && !localApiKey.isBlank()) {
                profile.setApiKey(localApiKey);
                changed = true;
            }
        }
        if (changed) {
            writeSharedState(stateWithApiKeys);
        }
    }

    private void updateProfileApiKey(String profileId, String apiKey) {
        State next = copyState(getActiveState());
        boolean changed = false;
        for (AiModelProfile profile : next.profiles) {
            if (Objects.equals(profileId, profile.getId())) {
                profile.setApiKey(apiKey);
                changed = true;
                break;
            }
        }
        if (changed) {
            saveActiveState(next);
        }
    }

    private State getActiveState() {
        if (PluginSettings.getInstance().getStorageMode() == PluginSettings.DataStorageMode.USER_SHARED) {
            return readSharedState(normalize(copyState(state)));
        }
        return state;
    }

    private void saveActiveState(State next) {
        State normalized = normalize(copyState(next));
        if (PluginSettings.getInstance().getStorageMode() == PluginSettings.DataStorageMode.USER_SHARED) {
            writeSharedState(normalized);
        } else {
            state = normalized;
        }
    }

    private State readSharedState(State defaultState) {
        return normalize(PluginDataStorage.readJson(
            PluginDataStorage.getSharedAiFeaturesPath(),
            State.class,
            normalize(copyState(defaultState))
        ));
    }

    private State withLegacyApiKeys(State source) {
        State next = normalize(copyState(source));
        for (AiModelProfile profile : next.profiles) {
            if (profile.hasApiKeySetting()) {
                continue;
            }
            String legacyApiKey = getPasswordSafeApiKey(profile.getId());
            if (legacyApiKey != null && !legacyApiKey.isBlank()) {
                profile.setApiKey(legacyApiKey);
            }
        }
        return next;
    }

    public static State copyState(State source) {
        State copy = new State();
        State safe = source == null ? new State() : source;
        copy.codeCompletionEnabled = safe.codeCompletionEnabled;
        copy.gitCommitMessageEnabled = safe.gitCommitMessageEnabled;
        copy.gitCommitMessageLanguage = safe.gitCommitMessageLanguage;
        copy.autoCompletionEnabled = safe.autoCompletionEnabled;
        copy.autoCompletionMaxTokens = safe.autoCompletionMaxTokens;
        copy.manualCompletionMaxTokens = safe.manualCompletionMaxTokens;
        copy.autoCompletionLengthLevel = safe.autoCompletionLengthLevel;
        copy.manualCompletionLengthLevel = safe.manualCompletionLengthLevel;
        copy.activeCompletionProfileId = safe.activeCompletionProfileId;
        copy.activeGitCommitProfileId = safe.activeGitCommitProfileId;
        copy.manualCompletionShortcut = safe.manualCompletionShortcut;
        copy.timingConfig = safe.timingConfig != null ? safe.timingConfig.copy() : new CompletionTimingConfig();
        copy.profiles = new ArrayList<>();
        if (safe.profiles != null) {
            for (AiModelProfile profile : safe.profiles) {
                if (profile != null) {
                    copy.profiles.add(profile.copy());
                }
            }
        }
        return normalize(copy);
    }

    public static State normalize(State source) {
        State normalized = source == null ? new State() : source;
        normalized.gitCommitMessageLanguage = parseGitCommitMessageLanguage(
            normalized.gitCommitMessageLanguage
        ).name();
        normalized.autoCompletionMaxTokens = clamp(normalized.autoCompletionMaxTokens, 16, 512, 64);
        normalized.manualCompletionMaxTokens = clamp(normalized.manualCompletionMaxTokens, 16, 1024, 160);
        normalized.timingConfig = normalized.timingConfig == null
            ? new CompletionTimingConfig()
            : normalized.timingConfig.copy();
        if (normalized.autoCompletionLengthLevel == null || normalized.autoCompletionLengthLevel.isBlank()) {
            normalized.autoCompletionLengthLevel = inferLengthLevel(normalized.autoCompletionMaxTokens, true).name();
        }
        if (normalized.manualCompletionLengthLevel == null || normalized.manualCompletionLengthLevel.isBlank()) {
            normalized.manualCompletionLengthLevel = inferLengthLevel(normalized.manualCompletionMaxTokens, false).name();
        }
        normalized.autoCompletionLengthLevel = parseLengthLevel(
            normalized.autoCompletionLengthLevel,
            AiCompletionLengthLevel.SINGLE_LINE
        ).name();
        normalized.manualCompletionLengthLevel = parseLengthLevel(
            normalized.manualCompletionLengthLevel,
            AiCompletionLengthLevel.SHORT
        ).name();
        if (normalized.manualCompletionShortcut == null || normalized.manualCompletionShortcut.isBlank()) {
            normalized.manualCompletionShortcut = DEFAULT_MANUAL_SHORTCUT;
        }
        if (normalized.profiles == null) {
            normalized.profiles = new ArrayList<>();
        }
        List<AiModelProfile> profiles = new ArrayList<>();
        for (AiModelProfile profile : normalized.profiles) {
            if (profile == null) {
                continue;
            }
            AiModelProfile copy = profile.copy();
            if (copy.getId() == null || copy.getId().isBlank()) {
                copy.setId(UUID.randomUUID().toString());
            }
            if (copy.getFormat() == null) {
                copy.setFormat(AiModelFormat.FIM_COMPLETIONS);
            }
            if (copy.getBaseUrl().isBlank()) {
                copy.setBaseUrl(copy.getFormat().getDefaultBaseUrl());
            }
            profiles.add(copy);
        }
        normalized.profiles = profiles;
        if (normalized.activeCompletionProfileId == null) {
            normalized.activeCompletionProfileId = "";
        }
        if (normalized.activeGitCommitProfileId == null) {
            normalized.activeGitCommitProfileId = "";
        }
        boolean activeExists = normalized.profiles.stream()
            .anyMatch(profile -> Objects.equals(profile.getId(), normalized.activeCompletionProfileId));
        if (!activeExists) {
            normalized.activeCompletionProfileId = normalized.profiles.isEmpty()
                ? ""
                : normalized.profiles.get(0).getId();
        }
        boolean activeGitExists = normalized.profiles.stream()
            .anyMatch(profile -> Objects.equals(profile.getId(), normalized.activeGitCommitProfileId));
        if (!activeGitExists) {
            normalized.activeGitCommitProfileId = defaultGitCommitProfileId(normalized.profiles);
        }
        return normalized;
    }

    public static boolean isNativeFimFormat(AiModelFormat format) {
        return format == AiModelFormat.FIM_COMPLETIONS
            || format == AiModelFormat.FIM_CHAT_COMPLETIONS;
    }

    public static boolean supportsGitCommitFormat(AiModelFormat format) {
        return format != null;
    }

    public enum GitCommitMessageLanguage {
        CHINESE,
        ENGLISH,
        JAPANESE
    }

    private static int clamp(int value, int min, int max, int fallback) {
        if (value <= 0) {
            return fallback;
        }
        return Math.max(min, Math.min(max, value));
    }

    private static AiCompletionLengthLevel parseLengthLevel(String value, AiCompletionLengthLevel fallback) {
        try {
            return AiCompletionLengthLevel.valueOf(value);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static GitCommitMessageLanguage parseGitCommitMessageLanguage(String value) {
        try {
            return GitCommitMessageLanguage.valueOf(value);
        } catch (Exception ignored) {
            return GitCommitMessageLanguage.CHINESE;
        }
    }

    private static AiCompletionLengthLevel inferLengthLevel(int tokens, boolean auto) {
        if (tokens <= 40) {
            return AiCompletionLengthLevel.SINGLE_LINE;
        }
        if (tokens <= 96) {
            return AiCompletionLengthLevel.SHORT;
        }
        if (tokens <= 220) {
            return auto ? AiCompletionLengthLevel.SHORT : AiCompletionLengthLevel.MEDIUM;
        }
        return AiCompletionLengthLevel.LONG;
    }

    private static String defaultGitCommitProfileId(List<AiModelProfile> profiles) {
        if (profiles == null || profiles.isEmpty()) {
            return "";
        }
        for (AiModelProfile profile : profiles) {
            if (supportsGitCommitFormat(profile.getFormat())) {
                return profile.getId();
            }
        }
        return profiles.get(0).getId();
    }
}
