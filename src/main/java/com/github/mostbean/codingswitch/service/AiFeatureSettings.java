package com.github.mostbean.codingswitch.service;

import com.github.mostbean.codingswitch.model.AiCompletionTriggerMode;
import com.github.mostbean.codingswitch.model.AiCompletionLengthLevel;
import com.github.mostbean.codingswitch.model.AiModelFormat;
import com.github.mostbean.codingswitch.model.AiModelProfile;
import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.CredentialAttributesKt;
import com.intellij.credentialStore.Credentials;
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
    public static final String DEFAULT_MANUAL_SHORTCUT = "alt F";

    public static class State {
        public boolean codeCompletionEnabled = false;
        public boolean gitCommitMessageEnabled = false;
        public boolean autoCompletionEnabled = false;
        public int autoCompletionMaxTokens = 64;
        public int manualCompletionMaxTokens = 160;
        public String autoCompletionLengthLevel = AiCompletionLengthLevel.SINGLE_LINE.name();
        public String manualCompletionLengthLevel = AiCompletionLengthLevel.MEDIUM.name();
        public String activeCompletionProfileId = "";
        public String manualCompletionShortcut = DEFAULT_MANUAL_SHORTCUT;
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
        return copyState(state);
    }

    public void update(State next) {
        this.state = normalize(copyState(next));
    }

    public boolean isCodeCompletionEnabled() {
        return state.codeCompletionEnabled;
    }

    public boolean isAutoCompletionEnabled() {
        return state.autoCompletionEnabled;
    }

    public boolean isGitCommitMessageEnabled() {
        return state.gitCommitMessageEnabled;
    }

    public int getCompletionMaxTokens(AiCompletionTriggerMode mode) {
        return getCompletionLengthLevel(mode).getMaxTokens();
    }

    public AiCompletionLengthLevel getCompletionLengthLevel(AiCompletionTriggerMode mode) {
        return mode == AiCompletionTriggerMode.MANUAL
            ? parseLengthLevel(state.manualCompletionLengthLevel, AiCompletionLengthLevel.MEDIUM)
            : parseLengthLevel(state.autoCompletionLengthLevel, AiCompletionLengthLevel.SINGLE_LINE);
    }

    public AiModelProfile getActiveCompletionProfile() {
        String activeId = state.activeCompletionProfileId == null ? "" : state.activeCompletionProfileId;
        for (AiModelProfile profile : state.profiles) {
            if (Objects.equals(activeId, profile.getId())) {
                return profile.copy();
            }
        }
        return state.profiles.isEmpty() ? null : state.profiles.get(0).copy();
    }

    public String getApiKey(String profileId) {
        if (profileId == null || profileId.isBlank()) {
            return "";
        }
        String value = PasswordSafe.getInstance().getPassword(createCredentialAttributes(profileId));
        return value == null ? "" : value;
    }

    public void setApiKey(String profileId, String apiKey) {
        if (profileId == null || profileId.isBlank() || apiKey == null) {
            return;
        }
        PasswordSafe.getInstance().set(
            createCredentialAttributes(profileId),
            apiKey.isBlank() ? null : new Credentials("api-key", apiKey)
        );
    }

    public void clearApiKey(String profileId) {
        if (profileId == null || profileId.isBlank()) {
            return;
        }
        PasswordSafe.getInstance().set(createCredentialAttributes(profileId), null);
    }

    private CredentialAttributes createCredentialAttributes(String profileId) {
        return new CredentialAttributes(
            CredentialAttributesKt.generateServiceName(CREDENTIAL_SERVICE_NAME, profileId)
        );
    }

    public static State copyState(State source) {
        State copy = new State();
        State safe = source == null ? new State() : source;
        copy.codeCompletionEnabled = safe.codeCompletionEnabled;
        copy.gitCommitMessageEnabled = safe.gitCommitMessageEnabled;
        copy.autoCompletionEnabled = safe.autoCompletionEnabled;
        copy.autoCompletionMaxTokens = safe.autoCompletionMaxTokens;
        copy.manualCompletionMaxTokens = safe.manualCompletionMaxTokens;
        copy.autoCompletionLengthLevel = safe.autoCompletionLengthLevel;
        copy.manualCompletionLengthLevel = safe.manualCompletionLengthLevel;
        copy.activeCompletionProfileId = safe.activeCompletionProfileId;
        copy.manualCompletionShortcut = safe.manualCompletionShortcut;
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
        normalized.autoCompletionMaxTokens = clamp(normalized.autoCompletionMaxTokens, 16, 512, 64);
        normalized.manualCompletionMaxTokens = clamp(normalized.manualCompletionMaxTokens, 16, 1024, 160);
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
            AiCompletionLengthLevel.MEDIUM
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
                copy.setFormat(AiModelFormat.OPENAI_RESPONSES);
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
        boolean activeExists = normalized.profiles.stream()
            .anyMatch(profile -> Objects.equals(profile.getId(), normalized.activeCompletionProfileId));
        if (!activeExists) {
            normalized.activeCompletionProfileId = normalized.profiles.isEmpty()
                ? ""
                : normalized.profiles.get(0).getId();
        }
        return normalized;
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
}
