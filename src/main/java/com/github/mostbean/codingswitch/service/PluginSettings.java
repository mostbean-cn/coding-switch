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
