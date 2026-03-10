package com.github.mostbean.codingswitch.service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

final class CodexAuthSupport {

    enum CodexAuthState {
        NONE,
        API_KEY,
        OFFICIAL_LOGIN
    }

    private CodexAuthSupport() {
    }

    static CodexAuthState detectState(String rawAuthJson) {
        JsonObject auth = parseObject(rawAuthJson);
        if (auth == null || auth.keySet().isEmpty()) {
            return CodexAuthState.NONE;
        }
        if (isValidOfficialLoginAuth(auth)) {
            return CodexAuthState.OFFICIAL_LOGIN;
        }
        if (hasNonBlankString(auth, "OPENAI_API_KEY")) {
            return CodexAuthState.API_KEY;
        }
        return CodexAuthState.NONE;
    }

    static boolean isValidOfficialLoginAuth(String rawAuthJson) {
        JsonObject auth = parseObject(rawAuthJson);
        return auth != null && isValidOfficialLoginAuth(auth);
    }

    static boolean isValidOfficialLoginAuth(JsonObject auth) {
        if (auth == null || auth.keySet().isEmpty()) {
            return false;
        }
        if (!hasNonBlankString(auth, "auth_mode") || !hasNonBlankString(auth, "last_refresh")) {
            return false;
        }
        if (!auth.has("tokens") || !auth.get("tokens").isJsonObject()) {
            return false;
        }
        JsonObject tokens = auth.getAsJsonObject("tokens");
        return hasNonBlankString(tokens, "id_token")
                && hasNonBlankString(tokens, "access_token")
                && hasNonBlankString(tokens, "refresh_token");
    }

    static JsonObject parseObject(String rawAuthJson) {
        if (rawAuthJson == null || rawAuthJson.isBlank()) {
            return null;
        }
        try {
            return JsonParser.parseString(rawAuthJson).getAsJsonObject();
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
