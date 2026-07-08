package com.github.mostbean.codingswitch.service;

import com.intellij.util.messages.Topic;

/**
 * 语言切换监听器接口。
 * 当用户在设置中切换界面语言时，会通知所有订阅者进行 UI 刷新。
 */
public interface LanguageChangedListener {

    Topic<LanguageChangedListener> TOPIC = Topic.create(
        "CodingSwitch.LanguageChanged",
        LanguageChangedListener.class
    );

    /**
     * 语言已切换的回调。
     *
     * @param oldLanguage 旧语言
     * @param newLanguage 新语言
     */
    void languageChanged(PluginSettings.Language oldLanguage, PluginSettings.Language newLanguage);
}
