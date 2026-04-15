package com.github.mostbean.codingswitch.service;

import com.github.mostbean.codingswitch.model.Provider;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.CredentialAttributesKt;
import com.intellij.credentialStore.Credentials;
import com.intellij.ide.passwordSafe.PasswordSafe;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Gemini 登录快照服务。
 * 通过 JetBrains PasswordSafe 安全保存和恢复 Gemini OAuth 登录状态，
 * 实现多账号快速切换，无需重新登录。
 */
@Service(Service.Level.APP)
public final class GeminiAuthSnapshotService {
    private record SnapshotPayload(String oauthJson, String googleAccountsJson) {
    }

    public enum RestoreResult {
        RESTORED,
        NO_SNAPSHOT,
        INVALID_SNAPSHOT
    }

    private static final String SERVICE_NAME = "com.github.mostbean.codingswitch.gemini.auth.snapshot";
    private static final String SNAPSHOT_USERNAME = "gemini-official-login";
    private static final String SNAPSHOT_VERSION_KEY = "version";
    private static final String SNAPSHOT_OAUTH_KEY = "oauth";
    private static final String SNAPSHOT_GOOGLE_ACCOUNTS_KEY = "googleAccounts";
    private static final int SNAPSHOT_VERSION = 2;

    public static GeminiAuthSnapshotService getInstance() {
        return ApplicationManager.getApplication().getService(GeminiAuthSnapshotService.class);
    }

    /**
     * 从 live 配置捕获登录快照。
     * 读取 ~/.gemini/oauth_creds.json / google_accounts.json，保存完整官方登录态。
     */
    public void captureFromLive(Provider provider) {
        if (provider == null) {
            return;
        }
        captureFromLive(provider.getEffectiveAuthBindingKey(), provider.getId());
    }

    public void captureFromLive(String providerId) {
        captureFromLive(providerId, providerId);
    }

    private void captureFromLive(String primaryKey, String legacyKey) {
        if (isBlank(primaryKey)) {
            return;
        }
        ConfigFileService configFileService = ConfigFileService.getInstance();
        String rawOAuthJson = configFileService.readGeminiOAuthRaw();
        if (GeminiAuthSupport.isValidOfficialLoginAuth(rawOAuthJson)) {
            String rawGoogleAccountsJson = configFileService.readGeminiGoogleAccountsRaw();
            PasswordSafe.getInstance().set(
                    createAttributes(primaryKey),
                    new Credentials(SNAPSHOT_USERNAME, serializeSnapshot(rawOAuthJson, rawGoogleAccountsJson)));
            clearLegacySnapshot(primaryKey, legacyKey);
            return;
        }
        clearSnapshot(primaryKey, legacyKey);
    }

    /**
     * 从快照恢复登录态到 live 配置。
     * 从 PasswordSafe 读取快照，恢复 ~/.gemini/oauth_creds.json / google_accounts.json。
     */
    public RestoreResult restoreToLive(Provider provider) throws IOException {
        if (provider == null) {
            ConfigFileService configFileService = ConfigFileService.getInstance();
            configFileService.deleteGeminiOAuthFile();
            configFileService.deleteGeminiGoogleAccountsFile();
            return RestoreResult.NO_SNAPSHOT;
        }
        return restoreToLive(provider.getEffectiveAuthBindingKey(), provider.getId());
    }

    public RestoreResult restoreToLive(String providerId) throws IOException {
        return restoreToLive(providerId, providerId);
    }

    private RestoreResult restoreToLive(String primaryKey, String legacyKey) throws IOException {
        ConfigFileService configFileService = ConfigFileService.getInstance();
        String snapshotKey = findExistingSnapshotKey(primaryKey, legacyKey);
        if (snapshotKey == null) {
            configFileService.deleteGeminiOAuthFile();
            configFileService.deleteGeminiGoogleAccountsFile();
            return RestoreResult.NO_SNAPSHOT;
        }

        String rawSnapshot = PasswordSafe.getInstance().getPassword(createAttributes(snapshotKey));
        SnapshotPayload snapshot = parseSnapshot(rawSnapshot);
        if (snapshot == null || !GeminiAuthSupport.isValidOfficialLoginAuth(snapshot.oauthJson())) {
            clearSnapshot(primaryKey, legacyKey);
            configFileService.deleteGeminiOAuthFile();
            configFileService.deleteGeminiGoogleAccountsFile();
            return RestoreResult.INVALID_SNAPSHOT;
        }

        if (!snapshotKey.equals(primaryKey)) {
            PasswordSafe.getInstance().set(
                    createAttributes(primaryKey),
                    new Credentials(SNAPSHOT_USERNAME, rawSnapshot.trim()));
            clearLegacySnapshot(primaryKey, legacyKey);
        }

        configFileService.writeGeminiOAuthRaw(snapshot.oauthJson().trim());
        if (snapshot.googleAccountsJson() != null
                && GeminiAuthSupport.isValidGoogleAccountsState(snapshot.googleAccountsJson())) {
            configFileService.writeGeminiGoogleAccountsRaw(snapshot.googleAccountsJson().trim());
        } else {
            // 兼容旧快照：清理旧账号缓存，交给 Gemini CLI 用恢复后的 OAuth 重新探测当前账号。
            configFileService.deleteGeminiGoogleAccountsFile();
        }
        return RestoreResult.RESTORED;
    }

    /**
     * 清除指定 Provider 的登录快照。
     */
    public void clearSnapshot(Provider provider) {
        if (provider == null) {
            return;
        }
        clearSnapshot(provider.getEffectiveAuthBindingKey(), provider.getId());
    }

    public void clearSnapshot(String providerId) {
        clearSnapshot(providerId, providerId);
    }

    private void clearSnapshot(String primaryKey, String legacyKey) {
        for (String key : snapshotKeys(primaryKey, legacyKey)) {
            PasswordSafe.getInstance().set(createAttributes(key), null);
        }
    }

    /**
     * 检查是否存在有效的登录快照。
     */
    public boolean hasSnapshot(Provider provider) {
        if (provider == null) {
            return false;
        }
        return hasSnapshot(provider.getEffectiveAuthBindingKey(), provider.getId());
    }

    public boolean hasSnapshot(String providerId) {
        return hasSnapshot(providerId, providerId);
    }

    private boolean hasSnapshot(String primaryKey, String legacyKey) {
        return findExistingSnapshotKey(primaryKey, legacyKey) != null;
    }

    private String findExistingSnapshotKey(String primaryKey, String legacyKey) {
        for (String key : snapshotKeys(primaryKey, legacyKey)) {
            String rawOAuthJson = PasswordSafe.getInstance().getPassword(createAttributes(key));
            if (!isBlank(rawOAuthJson)) {
                return key;
            }
        }
        return null;
    }

    private void clearLegacySnapshot(String primaryKey, String legacyKey) {
        if (isBlank(legacyKey) || legacyKey.equals(primaryKey)) {
            return;
        }
        PasswordSafe.getInstance().set(createAttributes(legacyKey), null);
    }

    private Set<String> snapshotKeys(String primaryKey, String legacyKey) {
        Set<String> keys = new LinkedHashSet<>();
        if (!isBlank(primaryKey)) {
            keys.add(primaryKey);
        }
        if (!isBlank(legacyKey)) {
            keys.add(legacyKey);
        }
        return keys;
    }

    private CredentialAttributes createAttributes(String providerId) {
        return new CredentialAttributes(CredentialAttributesKt.generateServiceName(SERVICE_NAME, providerId));
    }

    private String serializeSnapshot(String rawOAuthJson, String rawGoogleAccountsJson) {
        JsonObject snapshot = new JsonObject();
        snapshot.addProperty(SNAPSHOT_VERSION_KEY, SNAPSHOT_VERSION);
        snapshot.add(SNAPSHOT_OAUTH_KEY, JsonParser.parseString(rawOAuthJson.trim()));
        if (GeminiAuthSupport.isValidGoogleAccountsState(rawGoogleAccountsJson)) {
            snapshot.add(SNAPSHOT_GOOGLE_ACCOUNTS_KEY, JsonParser.parseString(rawGoogleAccountsJson.trim()));
        }
        return snapshot.toString();
    }

    private SnapshotPayload parseSnapshot(String rawSnapshot) {
        if (GeminiAuthSupport.isValidOfficialLoginAuth(rawSnapshot)) {
            return new SnapshotPayload(rawSnapshot.trim(), null);
        }

        JsonObject snapshot = GeminiAuthSupport.parseObject(rawSnapshot);
        if (snapshot == null || snapshot.keySet().isEmpty()) {
            return null;
        }
        if (!snapshot.has(SNAPSHOT_OAUTH_KEY) || !snapshot.get(SNAPSHOT_OAUTH_KEY).isJsonObject()) {
            return null;
        }

        String oauthJson = snapshot.getAsJsonObject(SNAPSHOT_OAUTH_KEY).toString();
        if (!GeminiAuthSupport.isValidOfficialLoginAuth(oauthJson)) {
            return null;
        }

        String googleAccountsJson = null;
        if (snapshot.has(SNAPSHOT_GOOGLE_ACCOUNTS_KEY) && snapshot.get(SNAPSHOT_GOOGLE_ACCOUNTS_KEY).isJsonObject()) {
            googleAccountsJson = snapshot.getAsJsonObject(SNAPSHOT_GOOGLE_ACCOUNTS_KEY).toString();
        }
        return new SnapshotPayload(oauthJson, googleAccountsJson);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
