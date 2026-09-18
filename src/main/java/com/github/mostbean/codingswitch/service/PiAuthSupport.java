package com.github.mostbean.codingswitch.service;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Pi auth.json 凭证检测。官方登录以 type=oauth 且包含 access/refresh 为准。
 */
final class PiAuthSupport {

    enum PiAuthState {
        NONE,
        OFFICIAL_LOGIN
    }

    private PiAuthSupport() {
    }

    static PiAuthState detectState(String rawAuthJson) {
        return isValidOfficialLoginAuth(rawAuthJson) ? PiAuthState.OFFICIAL_LOGIN : PiAuthState.NONE;
    }

    static boolean isValidOfficialLoginAuth(String rawAuthJson) {
        JsonObject auth = parseObject(rawAuthJson);
        return auth != null && isValidOfficialLoginAuth(auth);
    }

    static boolean isValidOfficialLoginAuth(JsonObject auth) {
        if (auth == null) {
            return false;
        }
        for (String key : auth.keySet()) {
            JsonElement value = auth.get(key);
            if (value != null && value.isJsonObject() && isOauthCredential(value.getAsJsonObject())) {
                return true;
            }
        }
        return false;
    }

    static JsonObject parseObject(String rawAuthJson) {
        if (rawAuthJson == null || rawAuthJson.isBlank()) {
            return null;
        }
        try {
            JsonElement parsed = JsonParser.parseString(rawAuthJson);
            return parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean isOauthCredential(JsonObject credential) {
        if (credential == null) {
            return false;
        }
        String type = getString(credential, "type");
        if (type == null || !"oauth".equalsIgnoreCase(type.trim())) {
            return false;
        }
        return hasNonBlankString(credential, "access") || hasNonBlankString(credential, "refresh");
    }

    private static String getString(JsonObject json, String key) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
            return null;
        }
        try {
            String value = json.get(key).getAsString();
            return value == null || value.isBlank() ? null : value;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean hasNonBlankString(JsonObject json, String key) {
        return getString(json, key) != null;
    }
}
