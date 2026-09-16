package com.github.mostbean.codingswitch.service;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Locale;
import java.util.Set;

final class GrokAuthSupport {

    enum GrokAuthState {
        NONE,
        OFFICIAL_LOGIN
    }

    private static final Set<String> TOKEN_KEYS = Set.of(
            "access_token",
            "refresh_token",
            "id_token",
            "session_token",
            "token"
    );

    private GrokAuthSupport() {
    }

    static GrokAuthState detectState(String rawAuthJson) {
        return isValidOfficialLoginAuth(rawAuthJson) ? GrokAuthState.OFFICIAL_LOGIN : GrokAuthState.NONE;
    }

    static boolean isValidOfficialLoginAuth(String rawAuthJson) {
        JsonObject auth = parseObject(rawAuthJson);
        return auth != null && isValidOfficialLoginAuth(auth);
    }

    static boolean isValidOfficialLoginAuth(JsonObject auth) {
        return auth != null && containsToken(auth);
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

    private static boolean containsToken(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return false;
        }
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            for (String key : object.keySet()) {
                if (isTokenKey(key) && hasNonBlankString(object, key)) {
                    return true;
                }
                if (containsToken(object.get(key))) {
                    return true;
                }
            }
            return false;
        }
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                if (containsToken(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isTokenKey(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        String normalized = key.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return TOKEN_KEYS.contains(normalized);
    }

    private static boolean hasNonBlankString(JsonObject json, String key) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
            return false;
        }
        try {
            String value = json.get(key).getAsString();
            return value != null && !value.isBlank();
        } catch (Exception ignored) {
            return false;
        }
    }
}
