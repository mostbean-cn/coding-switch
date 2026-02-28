package com.github.mostbean.codingswitch.service;

import java.text.MessageFormat;
import java.util.EnumMap;
import java.util.Map;

/**
 * 简单国际化工具：根据插件设置语言返回文案。
 */
public final class I18n {

    private static final Map<PluginSettings.Language, Map<String, String>> MESSAGES = createMessages();

    private I18n() {
    }

    public static PluginSettings.Language currentLanguage() {
        try {
            return PluginSettings.getInstance().getLanguage();
        } catch (Exception ignored) {
            return PluginSettings.Language.ZH;
        }
    }

    public static String t(String key, Object... args) {
        String template = resolveMessage(key, currentLanguage());
        return args == null || args.length == 0 ? template : MessageFormat.format(template, args);
    }

    private static String resolveMessage(String key, PluginSettings.Language language) {
        Map<String, String> selected = MESSAGES.get(language);
        if (selected != null && selected.containsKey(key)) {
            return selected.get(key);
        }
        Map<String, String> zh = MESSAGES.get(PluginSettings.Language.ZH);
        if (zh != null && zh.containsKey(key)) {
            return zh.get(key);
        }
        return key;
    }

    private static Map<PluginSettings.Language, Map<String, String>> createMessages() {
        Map<PluginSettings.Language, Map<String, String>> result = new EnumMap<>(PluginSettings.Language.class);

        result.put(PluginSettings.Language.ZH, Map.ofEntries(
                Map.entry("toolwindow.tab.providers", "配置管理"),
                Map.entry("toolwindow.tab.sessions", "会话管理"),
                Map.entry("toolwindow.tab.mcp", "MCP 服务"),
                Map.entry("toolwindow.tab.skills", "Skills"),
                Map.entry("toolwindow.tab.prompts", "提示词"),
                Map.entry("toolwindow.tab.settings", "设置"),

                Map.entry("settings.section.versionStatus", "CLI 版本状态"),
                Map.entry("settings.section.installCommands", "安装/更新命令"),
                Map.entry("settings.section.preferences", "偏好设置"),

                Map.entry("settings.table.cli", "CLI"),
                Map.entry("settings.table.currentVersion", "当前版本"),
                Map.entry("settings.table.latestVersion", "最新版本"),

                Map.entry("settings.status.checking", "检测中..."),
                Map.entry("settings.status.notInstalled", "未安装"),
                Map.entry("settings.status.latest", "v{0} ✓ 已是最新"),
                Map.entry("settings.status.updatable", "v{0} ⬆ 可更新"),

                Map.entry("settings.button.checkAllVersions", "检测全部版本"),
                Map.entry("settings.tooltip.copyClipboard", "复制到剪贴板"),
                Map.entry("settings.label.uiLanguage", "界面语言:"),
                Map.entry("settings.hint.restartRequired", "⚠️ 切换语言后需要重启 IDE 才能完全生效"),

                Map.entry("settings.dialog.languageChanged.message", "语言已切换为 {0}\n\n需要重启 IDE 才能完全生效。\n是否立即重启 IDE？"),
                Map.entry("settings.dialog.languageChanged.title", "语言设置已更改"),
                Map.entry("settings.dialog.languageChanged.restartNow", "立即重启"),
                Map.entry("settings.dialog.languageChanged.restartLater", "稍后手动重启")));

        result.put(PluginSettings.Language.EN, Map.ofEntries(
                Map.entry("toolwindow.tab.providers", "Providers"),
                Map.entry("toolwindow.tab.sessions", "Sessions"),
                Map.entry("toolwindow.tab.mcp", "MCP"),
                Map.entry("toolwindow.tab.skills", "Skills"),
                Map.entry("toolwindow.tab.prompts", "Prompts"),
                Map.entry("toolwindow.tab.settings", "Settings"),

                Map.entry("settings.section.versionStatus", "CLI Version Status"),
                Map.entry("settings.section.installCommands", "Install/Update Commands"),
                Map.entry("settings.section.preferences", "Preferences"),

                Map.entry("settings.table.cli", "CLI"),
                Map.entry("settings.table.currentVersion", "Current Version"),
                Map.entry("settings.table.latestVersion", "Latest Version"),

                Map.entry("settings.status.checking", "Checking..."),
                Map.entry("settings.status.notInstalled", "Not Installed"),
                Map.entry("settings.status.latest", "v{0} ✓ Up to date"),
                Map.entry("settings.status.updatable", "v{0} ⬆ Update available"),

                Map.entry("settings.button.checkAllVersions", "Check All Versions"),
                Map.entry("settings.tooltip.copyClipboard", "Copy to Clipboard"),
                Map.entry("settings.label.uiLanguage", "UI Language:"),
                Map.entry("settings.hint.restartRequired", "⚠️ Restart IDE after switching language for full effect"),

                Map.entry("settings.dialog.languageChanged.message", "Language switched to {0}\n\nRestart IDE for full effect.\nRestart now?"),
                Map.entry("settings.dialog.languageChanged.title", "Language Changed"),
                Map.entry("settings.dialog.languageChanged.restartNow", "Restart Now"),
                Map.entry("settings.dialog.languageChanged.restartLater", "Later")));

        return result;
    }
}
