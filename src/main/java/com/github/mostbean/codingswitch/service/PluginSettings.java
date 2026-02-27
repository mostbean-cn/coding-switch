package com.github.mostbean.codingswitch.service;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.*;
import org.jetbrains.annotations.NotNull;

/**
 * 插件全局设置（持久化存储）。
 * 目前包含语言偏好。
 */
@Service(Service.Level.APP)
@State(name = "CodingSwitchSettings", storages = @Storage("codingSwitchSettings.xml"))
public final class PluginSettings implements PersistentStateComponent<PluginSettings.State> {

    public enum Language {
        ZH("中文"),
        EN("English");

        private final String displayName;

        Language(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    public static class State {
        public String language = Language.ZH.name();
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

    public boolean isChinese() {
        return getLanguage() == Language.ZH;
    }
}
