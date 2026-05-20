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
import com.intellij.openapi.diagnostic.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

@Service(Service.Level.APP)
public final class AntigravityAuthSnapshotService {

    private static final Logger LOG = Logger.getInstance(AntigravityAuthSnapshotService.class);

    public enum RestoreResult {
        RESTORED,
        NO_SNAPSHOT,
        INVALID_SNAPSHOT
    }

    private static final String SERVICE_NAME = "com.github.mostbean.codingswitch.antigravity.auth.snapshot";
    private static final String SNAPSHOT_USERNAME = "antigravity-official-login";

    public static AntigravityAuthSnapshotService getInstance() {
        return ApplicationManager.getApplication().getService(AntigravityAuthSnapshotService.class);
    }

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
        ConfigFileService cfs = ConfigFileService.getInstance();
        AuthFiles authFiles = readLiveAuthFiles(cfs);

        if (authFiles != null) {
            JsonObject snapshot = new JsonObject();
            snapshot.add("oauth_creds", authFiles.oauthCreds());
            snapshot.add("google_accounts", authFiles.googleAccounts());
            String rawJson = snapshot.toString();

            PasswordSafe.getInstance().set(
                    createAttributes(primaryKey),
                    new Credentials(SNAPSHOT_USERNAME, rawJson.trim()));
            clearLegacySnapshot(primaryKey, legacyKey);
            return;
        }
        clearSnapshot(primaryKey, legacyKey);
    }

    public RestoreResult restoreToLive(Provider provider) throws IOException {
        if (provider == null) {
            deleteLiveAuthFiles();
            return RestoreResult.NO_SNAPSHOT;
        }
        return restoreToLive(provider.getEffectiveAuthBindingKey(), provider.getId());
    }

    public RestoreResult restoreToLive(String providerId) throws IOException {
        return restoreToLive(providerId, providerId);
    }

    private RestoreResult restoreToLive(String primaryKey, String legacyKey) throws IOException {
        String snapshotKey = findExistingSnapshotKey(primaryKey, legacyKey);
        if (snapshotKey == null) {
            deleteLiveAuthFiles();
            return RestoreResult.NO_SNAPSHOT;
        }

        String rawJson = PasswordSafe.getInstance().getPassword(createAttributes(snapshotKey));
        JsonObject snapshot = parseObject(rawJson);
        if (snapshot == null || !snapshot.has("oauth_creds") || !snapshot.has("google_accounts")) {
            clearSnapshot(primaryKey, legacyKey);
            deleteLiveAuthFiles();
            return RestoreResult.INVALID_SNAPSHOT;
        }

        JsonObject oauthCreds = snapshot.getAsJsonObject("oauth_creds");
        JsonObject googleAccounts = snapshot.getAsJsonObject("google_accounts");
        if (!isValidOfficialLoginAuth(oauthCreds, googleAccounts)) {
            clearSnapshot(primaryKey, legacyKey);
            deleteLiveAuthFiles();
            return RestoreResult.INVALID_SNAPSHOT;
        }

        if (!snapshotKey.equals(primaryKey)) {
            PasswordSafe.getInstance().set(
                    createAttributes(primaryKey),
                    new Credentials(SNAPSHOT_USERNAME, rawJson.trim()));
            clearLegacySnapshot(primaryKey, legacyKey);
        }

        ConfigFileService cfs = ConfigFileService.getInstance();
        writeLiveAuthFiles(cfs, oauthCreds, googleAccounts);
        return RestoreResult.RESTORED;
    }

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
            String rawJson = PasswordSafe.getInstance().getPassword(createAttributes(key));
            if (!isBlank(rawJson)) {
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

    private void deleteLiveAuthFiles() throws IOException {
        ConfigFileService cfs = ConfigFileService.getInstance();
        Set<Path> paths = new LinkedHashSet<>();
        paths.addAll(cfs.getAntigravityOAuthCredsFilePaths());
        paths.addAll(cfs.getAntigravityGoogleAccountsFilePaths());

        for (Path path : paths) {
            deleteIfExistsWithRetry(path);
        }
    }

    private void deleteIfExistsWithRetry(Path path) throws IOException {
        IOException failure = null;
        for (int i = 0; i < 3; i++) {
            try {
                Files.deleteIfExists(path);
                return;
            } catch (IOException e) {
                failure = e;
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        LOG.warn("Failed to delete Antigravity auth file after retries: " + path, failure);
        throw failure;
    }

    private AuthFiles readLiveAuthFiles(ConfigFileService cfs) {
        AuthFiles cliAuthFiles = readAuthFiles(
                cfs,
                cfs.getAntigravityCliOAuthCredsFilePath(),
                cfs.getAntigravityCliGoogleAccountsFilePath());
        if (cliAuthFiles != null) {
            return cliAuthFiles;
        }
        return readAuthFiles(
                cfs,
                cfs.getAntigravityOAuthCredsFilePath(),
                cfs.getAntigravityGoogleAccountsFilePath());
    }

    private AuthFiles readAuthFiles(ConfigFileService cfs, Path oauthPath, Path googlePath) {
        JsonObject oauthCreds = cfs.readJsonFile(oauthPath);
        JsonObject googleAccounts = cfs.readJsonFile(googlePath);
        if (!isValidOfficialLoginAuth(oauthCreds, googleAccounts)) {
            return null;
        }
        return new AuthFiles(oauthCreds, googleAccounts);
    }

    private void writeLiveAuthFiles(ConfigFileService cfs, JsonObject oauthCreds, JsonObject googleAccounts) throws IOException {
        for (Path path : cfs.getAntigravityOAuthCredsFilePaths()) {
            cfs.writeJsonFile(path, oauthCreds);
        }
        for (Path path : cfs.getAntigravityGoogleAccountsFilePaths()) {
            cfs.writeJsonFile(path, googleAccounts);
        }
    }

    private record AuthFiles(JsonObject oauthCreds, JsonObject googleAccounts) {
    }

    private static boolean isValidOfficialLoginAuth(JsonObject oauthCreds, JsonObject googleAccounts) {
        if (oauthCreds == null || oauthCreds.keySet().isEmpty()) {
            return false;
        }
        if (googleAccounts == null || googleAccounts.keySet().isEmpty()) {
            return false;
        }
        return hasNonBlankString(oauthCreds, "access_token")
                && hasNonBlankString(oauthCreds, "refresh_token")
                && hasNonBlankString(googleAccounts, "active");
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

    private static JsonObject parseObject(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return null;
        }
        try {
            return JsonParser.parseString(rawJson).getAsJsonObject();
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
