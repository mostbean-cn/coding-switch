package com.github.mostbean.codingswitch.service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

/**
 * Gemini 认证状态检测和验证工具类。
 * 用于判断 Gemini 当前的认证类型（无认证 / API Key / OAuth 官方登录）。
 */
final class GeminiAuthSupport {

    enum GeminiAuthState {
        NONE,           // 无认证
        API_KEY,        // API 密钥模式
        OFFICIAL_LOGIN  // 官方 OAuth 登录
    }

    private GeminiAuthSupport() {
    }

    /**
     * 根据 OAuth 文件内容检测认证状态。
     */
    static GeminiAuthState detectState(String rawOAuthJson) {
        JsonObject oauth = parseObject(rawOAuthJson);
        if (oauth == null || oauth.keySet().isEmpty()) {
            return GeminiAuthState.NONE;
        }
        if (isValidOfficialLoginAuth(oauth)) {
            return GeminiAuthState.OFFICIAL_LOGIN;
        }
        return GeminiAuthState.NONE;
    }

    /**
     * 判断原始 JSON 字符串是否为有效的 OAuth 登录凭证。
     */
    static boolean isValidOfficialLoginAuth(String rawOAuthJson) {
        JsonObject oauth = parseObject(rawOAuthJson);
        return oauth != null && isValidOfficialLoginAuth(oauth);
    }

    /**
     * 判断 JsonObject 是否为有效的 OAuth 登录凭证。
     * 有效的 OAuth 凭证需要包含：
     * - access_token
     * - refresh_token
     * - token_type
     * - expiry_date 或 expires_in
     */
    static boolean isValidOfficialLoginAuth(JsonObject oauth) {
        if (oauth == null || oauth.keySet().isEmpty()) {
            return false;
        }
        // 必须包含 access_token 和 refresh_token
        if (!hasNonBlankString(oauth, "access_token") || !hasNonBlankString(oauth, "refresh_token")) {
            return false;
        }
        // 必须有 token_type
        if (!hasNonBlankString(oauth, "token_type")) {
            return false;
        }
        // 必须有过期时间信息
        boolean hasExpiry = oauth.has("expiry_date") && !oauth.get("expiry_date").isJsonNull();
        boolean hasExpiresIn = oauth.has("expires_in") && !oauth.get("expires_in").isJsonNull();
        return hasExpiry || hasExpiresIn;
    }

    /**
     * 根据 .env 文件内容检测是否为 API Key 模式。
     */
    static boolean isApiKeyMode(String envContent) {
        if (envContent == null || envContent.isBlank()) {
            return false;
        }
        for (String line : envContent.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#") || trimmed.isEmpty()) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq > 0) {
                String key = trimmed.substring(0, eq).trim();
                String value = trimmed.substring(eq + 1).trim();
                if (key.equals("GEMINI_API_KEY") && !value.isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 判断 google_accounts.json 是否为有效结构。
     * 合法结构示例：{ "active": "foo@bar.com", "old": ["a@b.com"] }
     */
    static boolean isValidGoogleAccountsState(String rawGoogleAccountsJson) {
        JsonObject accounts = parseObject(rawGoogleAccountsJson);
        if (accounts == null) {
            return false;
        }
        if (accounts.has("active")
                && !accounts.get("active").isJsonNull()
                && !accounts.get("active").isJsonPrimitive()) {
            return false;
        }
        if (accounts.has("active")
                && !accounts.get("active").isJsonNull()
                && accounts.get("active").getAsString().isBlank()) {
            return false;
        }
        if (accounts.has("old") && !accounts.get("old").isJsonNull()) {
            if (!accounts.get("old").isJsonArray()) {
                return false;
            }
            JsonArray oldAccounts = accounts.getAsJsonArray("old");
            for (JsonElement element : oldAccounts) {
                if (!element.isJsonPrimitive() || element.getAsString().isBlank()) {
                    return false;
                }
            }
        }
        return true;
    }

    static JsonObject parseObject(String rawOAuthJson) {
        if (rawOAuthJson == null || rawOAuthJson.isBlank()) {
            return null;
        }
        try {
            return JsonParser.parseString(rawOAuthJson).getAsJsonObject();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean hasNonBlankString(JsonObject json, String key) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
            return false;
        }
        String value = json.get(key).getAsString();
        return value != null && !value.isBlank();
    }
}
