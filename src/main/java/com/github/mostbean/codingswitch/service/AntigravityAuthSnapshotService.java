package com.github.mostbean.codingswitch.service;

import com.github.mostbean.codingswitch.model.Provider;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
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
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

@Service(Service.Level.APP)
public final class AntigravityAuthSnapshotService {

    private static final Logger LOG = Logger.getInstance(AntigravityAuthSnapshotService.class);

    public enum RestoreResult {
        RESTORED,
        NO_SNAPSHOT,
        INVALID_SNAPSHOT
    }

    private static final String SERVICE_NAME = "com.github.mostbean.codingswitch.antigravity.auth.snapshot";
    private static final String SHARED_NAMESPACE = "antigravity";
    private static final String KEYRING_NAMESPACE = "antigravity-keyring";
    private static final List<String> KEYRING_TARGETS = List.of(
            "Antigravity Safe Storage",
            "Antigravity Safe Storage/Antigravity Key",
            "gemini:antigravity");
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
        boolean keyringCaptured = captureKeyringState(primaryKey);
        boolean runtimeCaptured = captureRuntimeState(cfs, primaryKey);
        AuthFiles authFiles = readLiveAuthFiles(cfs);

        if (authFiles != null) {
            JsonObject snapshot = new JsonObject();
            snapshot.add("oauth_creds", authFiles.oauthCreds());
            snapshot.add("google_accounts", authFiles.googleAccounts());
            String rawJson = snapshot.toString();

            saveSnapshot(primaryKey, rawJson.trim());
            clearLegacySnapshot(primaryKey, legacyKey);
            return;
        }
        if (runtimeCaptured || keyringCaptured) {
            clearLegacySnapshot(primaryKey, legacyKey);
            return;
        }
        clearSnapshot(primaryKey, legacyKey);
    }

    public RestoreResult restoreToLive(Provider provider) throws IOException {
        if (provider == null) {
            clearLiveAuthState();
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
            clearLiveAuthState();
            return RestoreResult.NO_SNAPSHOT;
        }

        ConfigFileService cfs = ConfigFileService.getInstance();
        String rawJson = readSnapshot(snapshotKey);
        JsonObject snapshot = parseObject(rawJson);
        boolean hasRuntimeSnapshot = hasRuntimeSnapshot(cfs, snapshotKey);
        boolean hasKeyringSnapshot = hasKeyringSnapshot(snapshotKey);
        boolean hasValidJsonSnapshot = snapshot != null
                && snapshot.has("oauth_creds")
                && snapshot.has("google_accounts")
                && isValidOfficialLoginAuth(
                        snapshot.getAsJsonObject("oauth_creds"),
                        snapshot.getAsJsonObject("google_accounts"));

        if (!hasValidJsonSnapshot && !hasRuntimeSnapshot && !hasKeyringSnapshot) {
            clearSnapshot(primaryKey, legacyKey);
            clearLiveAuthState();
            return RestoreResult.INVALID_SNAPSHOT;
        }

        clearRuntimeAuthState(cfs);
        clearKeyringState();
        if (hasRuntimeSnapshot) {
            restoreRuntimeState(cfs, snapshotKey);
        }
        if (hasKeyringSnapshot) {
            restoreKeyringState(snapshotKey);
        }
        if (hasValidJsonSnapshot) {
            writeLiveAuthFiles(
                    cfs,
                    snapshot.getAsJsonObject("oauth_creds"),
                    snapshot.getAsJsonObject("google_accounts"));
        }

        if (!snapshotKey.equals(primaryKey)) {
            if (hasValidJsonSnapshot) {
                saveSnapshot(primaryKey, rawJson.trim());
            }
            if (hasRuntimeSnapshot) {
                copyRuntimeSnapshot(cfs, snapshotKey, primaryKey);
            }
            if (hasKeyringSnapshot) {
                copyKeyringSnapshot(snapshotKey, primaryKey);
            }
            clearLegacySnapshot(primaryKey, legacyKey);
        }

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
            deleteSnapshot(key);
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
        ConfigFileService cfs = ConfigFileService.getInstance();
        for (String key : snapshotKeys(primaryKey, legacyKey)) {
            String rawJson = readSnapshot(key);
            if (!isBlank(rawJson) || hasRuntimeSnapshot(cfs, key) || hasKeyringSnapshot(key)) {
                return key;
            }
        }
        return null;
    }

    private void clearLegacySnapshot(String primaryKey, String legacyKey) {
        if (isBlank(legacyKey) || legacyKey.equals(primaryKey)) {
            return;
        }
        deleteSnapshot(legacyKey);
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

    private void saveSnapshot(String snapshotKey, String rawSnapshot) {
        PasswordSafe.getInstance().set(
                createAttributes(snapshotKey),
                new Credentials(SNAPSHOT_USERNAME, rawSnapshot));
        SharedAuthSnapshotStore.save(SHARED_NAMESPACE, snapshotKey, rawSnapshot);
    }

    private String readSnapshot(String snapshotKey) {
        String rawSnapshot = PasswordSafe.getInstance().getPassword(createAttributes(snapshotKey));
        if (!isBlank(rawSnapshot)) {
            return rawSnapshot;
        }
        return SharedAuthSnapshotStore.load(SHARED_NAMESPACE, snapshotKey);
    }

    private void deleteSnapshot(String snapshotKey) {
        PasswordSafe.getInstance().set(createAttributes(snapshotKey), null);
        SharedAuthSnapshotStore.delete(SHARED_NAMESPACE, snapshotKey);
        SharedAuthSnapshotStore.delete(KEYRING_NAMESPACE, snapshotKey);
        try {
            deleteRecursively(ConfigFileService.getInstance().getAntigravityRuntimeSnapshotDir(snapshotKey));
        } catch (IOException e) {
            LOG.warn("Failed to delete Antigravity runtime snapshot: " + snapshotKey, e);
        }
    }

    private boolean captureKeyringState(String snapshotKey) {
        JsonArray snapshots = new JsonArray();
        for (String target : KEYRING_TARGETS) {
            String rawSnapshot = WindowsCredentialStore.readGenericCredentialSnapshot(target);
            if (isBlank(rawSnapshot)) {
                continue;
            }
            try {
                snapshots.add(JsonParser.parseString(rawSnapshot));
            } catch (Exception e) {
                LOG.warn("Failed to parse Antigravity keyring snapshot: " + target, e);
            }
        }
        if (snapshots.size() == 0) {
            return false;
        }
        SharedAuthSnapshotStore.save(KEYRING_NAMESPACE, snapshotKey, snapshots.toString());
        return true;
    }

    private boolean hasKeyringSnapshot(String snapshotKey) {
        return !readKeyringSnapshots(snapshotKey).isEmpty();
    }

    private void restoreKeyringState(String snapshotKey) {
        for (String rawSnapshot : readKeyringSnapshots(snapshotKey)) {
            WindowsCredentialStore.writeGenericCredentialSnapshot(rawSnapshot);
        }
    }

    private void copyKeyringSnapshot(String fromKey, String toKey) {
        String rawSnapshot = SharedAuthSnapshotStore.load(KEYRING_NAMESPACE, fromKey);
        if (!isBlank(rawSnapshot)) {
            SharedAuthSnapshotStore.save(KEYRING_NAMESPACE, toKey, rawSnapshot);
        }
    }

    private void clearKeyringState() {
        for (String target : KEYRING_TARGETS) {
            WindowsCredentialStore.deleteGenericCredential(target);
        }
    }

    private List<String> readKeyringSnapshots(String snapshotKey) {
        String rawSnapshot = SharedAuthSnapshotStore.load(KEYRING_NAMESPACE, snapshotKey);
        if (isBlank(rawSnapshot)) {
            return List.of();
        }
        try {
            JsonElement parsed = JsonParser.parseString(rawSnapshot);
            if (parsed != null && parsed.isJsonArray()) {
                List<String> snapshots = new ArrayList<>();
                for (JsonElement item : parsed.getAsJsonArray()) {
                    if (item != null && item.isJsonObject()) {
                        snapshots.add(item.toString());
                    }
                }
                return snapshots;
            }
            if (parsed != null && parsed.isJsonObject()) {
                return List.of(parsed.toString());
            }
        } catch (Exception ignored) {
            return List.of(rawSnapshot);
        }
        return List.of();
    }

    private boolean captureRuntimeState(ConfigFileService cfs, String snapshotKey) {
        try {
            Path snapshotDir = cfs.getAntigravityRuntimeSnapshotDir(snapshotKey);
            deleteRecursively(snapshotDir);
            boolean captured = false;
            for (Path livePath : cfs.getAntigravityRuntimeAuthStatePaths()) {
                if (Files.exists(livePath)) {
                    copyRecursively(livePath, snapshotDir.resolve(toBackupFileName(livePath)));
                    captured = true;
                }
            }
            return captured;
        } catch (IOException e) {
            LOG.warn("Failed to capture Antigravity runtime snapshot: " + snapshotKey, e);
            return false;
        }
    }

    private boolean hasRuntimeSnapshot(ConfigFileService cfs, String snapshotKey) {
        Path snapshotDir = cfs.getAntigravityRuntimeSnapshotDir(snapshotKey);
        return Files.isDirectory(snapshotDir);
    }

    private void restoreRuntimeState(ConfigFileService cfs, String snapshotKey) throws IOException {
        Path snapshotDir = cfs.getAntigravityRuntimeSnapshotDir(snapshotKey);
        for (Path livePath : cfs.getAntigravityRuntimeAuthStatePaths()) {
            Path snapshotPath = snapshotDir.resolve(toBackupFileName(livePath));
            if (Files.exists(snapshotPath)) {
                copyRecursively(snapshotPath, livePath);
            }
        }
    }

    private void copyRuntimeSnapshot(ConfigFileService cfs, String fromKey, String toKey) throws IOException {
        Path fromDir = cfs.getAntigravityRuntimeSnapshotDir(fromKey);
        Path toDir = cfs.getAntigravityRuntimeSnapshotDir(toKey);
        if (!Files.exists(fromDir)) {
            return;
        }
        deleteRecursively(toDir);
        copyRecursively(fromDir, toDir);
    }

    private void clearLiveAuthState() throws IOException {
        ConfigFileService cfs = ConfigFileService.getInstance();
        Set<Path> paths = new LinkedHashSet<>();
        paths.addAll(cfs.getAntigravityOAuthCredsFilePaths());
        paths.addAll(cfs.getAntigravityGoogleAccountsFilePaths());
        paths.addAll(cfs.getAntigravityRuntimeAuthStatePaths());
        clearKeyringState();
        moveLiveAuthStateToBackup(cfs, paths);
    }

    private void clearRuntimeAuthState(ConfigFileService cfs) throws IOException {
        moveLiveAuthStateToBackup(cfs, new LinkedHashSet<>(cfs.getAntigravityRuntimeAuthStatePaths()));
    }

    private void moveLiveAuthStateToBackup(ConfigFileService cfs, Set<Path> paths) throws IOException {
        Path backupRoot = cfs.getAntigravityAuthResetBackupDir().resolve(String.valueOf(System.currentTimeMillis()));
        for (Path path : paths) {
            moveIfExistsWithRetry(path, backupRoot.resolve(toBackupFileName(path)));
        }
    }

    private void moveIfExistsWithRetry(Path path, Path backupPath) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        IOException failure = null;
        for (int i = 0; i < 3; i++) {
            try {
                Files.createDirectories(backupPath.getParent());
                Files.move(path, backupPath, StandardCopyOption.REPLACE_EXISTING);
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
        LOG.warn("Failed to isolate Antigravity auth state after retries: " + path, failure);
        throw failure;
    }

    private String toBackupFileName(Path path) {
        return path.toAbsolutePath().toString()
                .replace(':', '_')
                .replace('\\', '_')
                .replace('/', '_');
    }

    private void copyRecursively(Path source, Path target) throws IOException {
        if (Files.isDirectory(source)) {
            try (Stream<Path> stream = Files.walk(source)) {
                for (Path current : stream.toList()) {
                    Path relative = source.relativize(current);
                    Path destination = target.resolve(relative);
                    if (Files.isDirectory(current)) {
                        Files.createDirectories(destination);
                    } else {
                        Files.createDirectories(destination.getParent());
                        Files.copy(current, destination, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
            return;
        }
        Files.createDirectories(target.getParent());
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        if (!Files.isDirectory(path)) {
            Files.deleteIfExists(path);
            return;
        }
        try (Stream<Path> stream = Files.walk(path)) {
            for (Path current : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(current);
            }
        }
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
