package com.github.mostbean.codingswitch.service;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;

/**
 * 插件全局设置（持久化存储）。
 */
@Service(Service.Level.APP)
@State(name = "CodingSwitchSettings", storages = @Storage("codingSwitchSettings.xml"))
public final class PluginSettings implements PersistentStateComponent<PluginSettings.State> {

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

    public static class State {
        public String language = Language.ZH.name();
        public String githubToken = "";
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
        this.state = state;
    }

    public Language getLanguage() {
        try {
            return Language.valueOf(state.language);
        } catch (Exception e) {
            return Language.ZH;
        }
    }

    public void setLanguage(Language lang) {
        state.language = lang.name();
    }

    public String getGithubToken() {
        return state.githubToken == null ? "" : state.githubToken.trim();
    }

    public void setGithubToken(String token) {
        state.githubToken = token == null ? "" : token.trim();
    }

    public boolean isChinese() {
        return getLanguage() == Language.ZH;
    }
}
