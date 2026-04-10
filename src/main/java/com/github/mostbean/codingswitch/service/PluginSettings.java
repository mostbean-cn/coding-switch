package com.github.mostbean.codingswitch.service;

import com.github.mostbean.codingswitch.model.CliType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.NotNull;

/**
 * 插件全局设置（持久化存储）。
 */
@Service(Service.Level.APP)
@State(name = "CodingSwitchSettings", storages = @Storage("codingSwitchSettings.xml"))
public final class PluginSettings implements PersistentStateComponent<PluginSettings.State> {

    public enum DataStorageMode {
        IDE_LOCAL,
        USER_SHARED;

        public String getDisplayName(Language uiLanguage) {
            boolean englishUi = uiLanguage == Language.EN;
            return switch (this) {
                case IDE_LOCAL -> englishUi ? "IDE Local" : "IDE 本地";
                case USER_SHARED -> englishUi ? "User Shared" : "用户级共享";
            };
        }
    }

    public enum Language {
        ZH,
        EN;

        public String getDisplayName(Language uiLanguage) {
            boolean englishUi = uiLanguage == EN;
            return switch (this) {
                case ZH -> englishUi ? "Chinese" : "中文";
                case EN -> "English";
            };
        }
    }

    /**
     * 安全策略组合，用于配置 CLI 的 approval_policy 和 sandbox_mode。
     */
    public enum SecurityPolicy {
        /**
         * 默认策略：不写入配置文件，使用 CLI 内置默认值。
         */
        DEFAULT("default", null, null),
        /**
         * 完全自主 + 完全权限：无需批准，完全访问权限。
         */
        AUTO_FULL_ACCESS("auto-full-access", "never", "danger-full-access");

        private final String configValue;
        private final String approvalPolicy;
        private final String sandboxMode;

        SecurityPolicy(String configValue, String approvalPolicy, String sandboxMode) {
            this.configValue = configValue;
            this.approvalPolicy = approvalPolicy;
            this.sandboxMode = sandboxMode;
        }

        public String getConfigValue() {
            return configValue;
        }

        public String getApprovalPolicy() {
            return approvalPolicy;
        }

        public String getSandboxMode() {
            return sandboxMode;
        }

        /**
         * 是否为默认策略（不需要写入配置文件）。
         */
        public boolean isDefault() {
            return this == DEFAULT;
        }

        public String getDisplayName(Language uiLanguage) {
            boolean english = uiLanguage == Language.EN;
            return switch (this) {
                case DEFAULT -> english ? "Default Permissions" : "默认权限";
                case AUTO_FULL_ACCESS -> english
                    ? "Full Access Permissions"
                    : "完全访问权限";
            };
        }
    }

    public static class CliQuickLaunchItem {
        public String name = "";
        public String command = "";

        public CliQuickLaunchItem() {}

        public CliQuickLaunchItem(String name, String command) {
            this.name = name == null ? "" : name;
            this.command = command == null ? "" : command;
        }
    }

    public static class State {
        public String language = Language.ZH.name();
        public String githubToken = "";
        public String storageMode = DataStorageMode.IDE_LOCAL.name();
        public boolean cliQuickLaunchEnabled = false;
        public List<CliQuickLaunchItem> cliQuickLaunchItems = new ArrayList<>();
        public String cliQuickLaunchSelectedCommand = "";
        public String providerFilterCliId = "";
        public String sessionFilterCliId = "";
        public String promptFilterCliId = "";
    }

    private State state = new State();

    public static PluginSettings getInstance() {
        return ApplicationManager.getApplication().getService(PluginSettings.class);
    }

    @Override
    public @NotNull State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State state) {
        this.state = normalizeState(state);
    }

    public Language getLanguage() {
        try {
            return Language.valueOf(getActiveState().language);
        } catch (Exception e) {
            return Language.ZH;
        }
    }

    public void setLanguage(Language lang) {
        State active = getActiveState();
        active.language = lang.name();
        saveActiveState(active);
    }

    public String getGithubToken() {
        State active = getActiveState();
        return active.githubToken == null ? "" : active.githubToken.trim();
    }

    public void setGithubToken(String token) {
        State active = getActiveState();
        active.githubToken = token == null ? "" : token.trim();
        saveActiveState(active);
    }

    public boolean isCliQuickLaunchEnabled() {
        State active = getActiveState();
        return active.cliQuickLaunchEnabled;
    }

    public void setCliQuickLaunchEnabled(boolean enabled) {
        State active = getActiveState();
        active.cliQuickLaunchEnabled = enabled;
        saveActiveState(active);
    }

    public List<CliQuickLaunchItem> getCliQuickLaunchItems() {
        State active = getActiveState();
        return active.cliQuickLaunchItems == null
            ? new ArrayList<>()
            : new ArrayList<>(active.cliQuickLaunchItems);
    }

    public void setCliQuickLaunchItems(List<CliQuickLaunchItem> items) {
        State active = getActiveState();
        active.cliQuickLaunchItems = items == null
            ? new ArrayList<>()
            : new ArrayList<>(items);
        saveActiveState(active);
    }

    public String getCliQuickLaunchSelectedCommand() {
        State active = getActiveState();
        return active.cliQuickLaunchSelectedCommand == null ? "" : active.cliQuickLaunchSelectedCommand;
    }

    public void setCliQuickLaunchSelectedCommand(String command) {
        State active = getActiveState();
        active.cliQuickLaunchSelectedCommand = command == null ? "" : command;
        saveActiveState(active);
    }

    public CliType getProviderFilterCli() {
        return CliType.fromId(getActiveState().providerFilterCliId);
    }

    public void setProviderFilterCli(CliType cliType) {
        State active = getActiveState();
        active.providerFilterCliId = cliType == null ? "" : cliType.getId();
        saveActiveState(active);
    }

    public CliType getSessionFilterCli() {
        return CliType.fromId(getActiveState().sessionFilterCliId);
    }

    public void setSessionFilterCli(CliType cliType) {
        State active = getActiveState();
        active.sessionFilterCliId = cliType == null ? "" : cliType.getId();
        saveActiveState(active);
    }

    public CliType getPromptFilterCli() {
        return CliType.fromId(getActiveState().promptFilterCliId);
    }

    public void setPromptFilterCli(CliType cliType) {
        State active = getActiveState();
        active.promptFilterCliId = cliType == null ? "" : cliType.getId();
        saveActiveState(active);
    }

    public boolean isChinese() {
        return getLanguage() == Language.ZH;
    }

    public DataStorageMode getStorageMode() {
        try {
            return DataStorageMode.valueOf(state.storageMode);
        } catch (Exception e) {
            return DataStorageMode.IDE_LOCAL;
        }
    }

    public void setLocalStorageMode(DataStorageMode mode) {
        state.storageMode = mode == null ? DataStorageMode.IDE_LOCAL.name() : mode.name();
    }

    public State snapshotCurrentState() {
        State active = getActiveState();
        State snapshot = new State();
        snapshot.language = active.language;
        snapshot.githubToken = active.githubToken;
        snapshot.storageMode = getStorageMode().name();
        snapshot.cliQuickLaunchEnabled = active.cliQuickLaunchEnabled;
        snapshot.cliQuickLaunchItems = active.cliQuickLaunchItems == null ? new ArrayList<>() : new ArrayList<>(active.cliQuickLaunchItems);
        snapshot.cliQuickLaunchSelectedCommand = active.cliQuickLaunchSelectedCommand;
        snapshot.providerFilterCliId = active.providerFilterCliId;
        snapshot.sessionFilterCliId = active.sessionFilterCliId;
        snapshot.promptFilterCliId = active.promptFilterCliId;
        return normalizeState(snapshot);
    }

    public State snapshotLocalState() {
        return snapshotLocalStateInternal();
    }

    public State snapshotSharedState() {
        return readSharedState(new State());
    }

    public void overwriteLocalState(State newState) {
        this.state = normalizeState(newState);
    }

    public void writeSharedState(State newState) {
        PluginDataStorage.writeJson(PluginDataStorage.getSharedSettingsPath(), normalizeState(newState));
    }

    private State getActiveState() {
        if (getStorageMode() == DataStorageMode.USER_SHARED) {
            return readSharedState(snapshotLocalStateInternal());
        }
        return state;
    }

    private void saveActiveState(State active) {
        State normalized = normalizeState(active);
        if (getStorageMode() == DataStorageMode.USER_SHARED) {
            writeSharedState(normalized);
        } else {
            state = normalized;
        }
    }

    private State readSharedState(State defaultState) {
        return normalizeState(PluginDataStorage.readJson(
                PluginDataStorage.getSharedSettingsPath(),
                State.class,
                normalizeState(defaultState)));
    }

    private State snapshotLocalStateInternal() {
        State snapshot = new State();
        snapshot.language = state.language;
        snapshot.githubToken = state.githubToken;
        snapshot.storageMode = state.storageMode;
        snapshot.cliQuickLaunchEnabled = state.cliQuickLaunchEnabled;
        snapshot.cliQuickLaunchItems = state.cliQuickLaunchItems == null
            ? new ArrayList<>()
            : new ArrayList<>(state.cliQuickLaunchItems);
        snapshot.cliQuickLaunchSelectedCommand = state.cliQuickLaunchSelectedCommand;
        snapshot.providerFilterCliId = state.providerFilterCliId;
        snapshot.sessionFilterCliId = state.sessionFilterCliId;
        snapshot.promptFilterCliId = state.promptFilterCliId;
        return normalizeState(snapshot);
    }

    private static State normalizeState(State state) {
        State normalized = state == null ? new State() : state;
        if (normalized.language == null || normalized.language.isBlank()) {
            normalized.language = Language.ZH.name();
        }
        if (normalized.githubToken == null) {
            normalized.githubToken = "";
        }
        if (normalized.storageMode == null || normalized.storageMode.isBlank()) {
            normalized.storageMode = DataStorageMode.IDE_LOCAL.name();
        }
        if (normalized.cliQuickLaunchItems == null) {
            normalized.cliQuickLaunchItems = new ArrayList<>();
        }
        if (normalized.cliQuickLaunchSelectedCommand == null) {
            normalized.cliQuickLaunchSelectedCommand = "";
        }
        if (normalized.providerFilterCliId == null) {
            normalized.providerFilterCliId = "";
        }
        if (normalized.sessionFilterCliId == null) {
            normalized.sessionFilterCliId = "";
        }
        if (normalized.promptFilterCliId == null) {
            normalized.promptFilterCliId = "";
        }
        return normalized;
    }
}
