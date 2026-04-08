package com.github.mostbean.codingswitch.service;

import java.util.Map;

/**
 * Gemini 认证相关的国际化文本。
 * 作为辅助类被 I18n 调用。
 */
final class GeminiI18nMessages {

    private GeminiI18nMessages() {
    }

    static void addZhMessages(Map<String, String> m) {
        m.put("provider.dialog.geminiAuth.restored",
                "✅ 已恢复之前的登录状态，无需重新登录");
        m.put("provider.dialog.geminiAuth.loginRequired",
                "🔐 首次使用或无登录快照，请运行 CLI 完成官方登录");
        m.put("provider.dialog.geminiAuth.snapshotInvalid",
                "⚠️ 历史登录已失效，请重新登录");
    }

    static void addEnMessages(Map<String, String> m) {
        m.put("provider.dialog.geminiAuth.restored",
                "✅ Login state restored, no need to re-login");
        m.put("provider.dialog.geminiAuth.loginRequired",
                "🔐 First use or no login snapshot, please run CLI to complete official login");
        m.put("provider.dialog.geminiAuth.snapshotInvalid",
                "⚠️ Historical login snapshot is invalid, please re-login");
    }
}
