package com.github.mostbean.codingswitch.service;

import com.github.mostbean.codingswitch.model.Provider;
import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.CredentialAttributesKt;
import com.intellij.credentialStore.Credentials;
import com.intellij.ide.passwordSafe.PasswordSafe;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;

@Service(Service.Level.APP)
public final class CodexAuthSnapshotService {

    public enum RestoreResult {
        RESTORED,
        NO_SNAPSHOT,
        INVALID_SNAPSHOT
    }

    private static final String SERVICE_NAME = "com.github.mostbean.codingswitch.codex.auth.snapshot";
    private static final String SHARED_NAMESPACE = "codex";
    private static final String SNAPSHOT_USERNAME = "codex-official-login";

    public static CodexAuthSnapshotService getInstance() {
        return ApplicationManager.getApplication().getService(CodexAuthSnapshotService.class);
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
        ConfigFileService configFileService = ConfigFileService.getInstance();
        String rawAuthJson = configFileService.readCodexAuthRaw();
        if (CodexAuthSupport.isValidOfficialLoginAuth(rawAuthJson)) {
            saveSnapshot(primaryKey, rawAuthJson.trim());
            clearLegacySnapshot(primaryKey, legacyKey);
            return;
        }
        clearSnapshot(primaryKey, legacyKey);
    }

    public RestoreResult restoreToLive(Provider provider) throws IOException {
        if (provider == null) {
            ConfigFileService.getInstance().deleteCodexAuthFile();
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
            ConfigFileService.getInstance().deleteCodexAuthFile();
            return RestoreResult.NO_SNAPSHOT;
        }

        String rawAuthJson = readSnapshot(snapshotKey);
        if (!CodexAuthSupport.isValidOfficialLoginAuth(rawAuthJson)) {
            clearSnapshot(primaryKey, legacyKey);
            ConfigFileService.getInstance().deleteCodexAuthFile();
            return RestoreResult.INVALID_SNAPSHOT;
        }

        if (!snapshotKey.equals(primaryKey)) {
            saveSnapshot(primaryKey, rawAuthJson.trim());
            clearLegacySnapshot(primaryKey, legacyKey);
        }

        ConfigFileService.getInstance().writeCodexAuthRaw(rawAuthJson.trim());
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
        for (String key : snapshotKeys(primaryKey, legacyKey)) {
            String rawAuthJson = readSnapshot(key);
            if (!isBlank(rawAuthJson)) {
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
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
