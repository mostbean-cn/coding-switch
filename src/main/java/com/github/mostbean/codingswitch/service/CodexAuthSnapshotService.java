package com.github.mostbean.codingswitch.service;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.CredentialAttributesKt;
import com.intellij.credentialStore.Credentials;
import com.intellij.ide.passwordSafe.PasswordSafe;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;

import java.io.IOException;

@Service(Service.Level.APP)
public final class CodexAuthSnapshotService {

    public enum RestoreResult {
        RESTORED,
        NO_SNAPSHOT,
        INVALID_SNAPSHOT
    }

    private static final String SERVICE_NAME = "com.github.mostbean.codingswitch.codex.auth.snapshot";
    private static final String SNAPSHOT_USERNAME = "codex-official-login";

    public static CodexAuthSnapshotService getInstance() {
        return ApplicationManager.getApplication().getService(CodexAuthSnapshotService.class);
    }

    public void captureFromLive(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            return;
        }
        ConfigFileService configFileService = ConfigFileService.getInstance();
        String rawAuthJson = configFileService.readCodexAuthRaw();
        if (CodexAuthSupport.isValidOfficialLoginAuth(rawAuthJson)) {
            Credentials credentials = new Credentials(SNAPSHOT_USERNAME, rawAuthJson.trim());
            PasswordSafe.getInstance().set(createAttributes(providerId), credentials);
            return;
        }
        clearSnapshot(providerId);
    }

    public RestoreResult restoreToLive(String providerId) throws IOException {
        if (!hasSnapshot(providerId)) {
            ConfigFileService.getInstance().deleteCodexAuthFile();
            return RestoreResult.NO_SNAPSHOT;
        }

        String rawAuthJson = PasswordSafe.getInstance().getPassword(createAttributes(providerId));
        if (!CodexAuthSupport.isValidOfficialLoginAuth(rawAuthJson)) {
            clearSnapshot(providerId);
            ConfigFileService.getInstance().deleteCodexAuthFile();
            return RestoreResult.INVALID_SNAPSHOT;
        }

        ConfigFileService.getInstance().writeCodexAuthRaw(rawAuthJson.trim());
        return RestoreResult.RESTORED;
    }

    public void clearSnapshot(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            return;
        }
        PasswordSafe.getInstance().set(createAttributes(providerId), null);
    }

    public boolean hasSnapshot(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            return false;
        }
        String rawAuthJson = PasswordSafe.getInstance().getPassword(createAttributes(providerId));
        return rawAuthJson != null && !rawAuthJson.isBlank();
    }

    private CredentialAttributes createAttributes(String providerId) {
        return new CredentialAttributes(CredentialAttributesKt.generateServiceName(SERVICE_NAME, providerId));
    }
}
