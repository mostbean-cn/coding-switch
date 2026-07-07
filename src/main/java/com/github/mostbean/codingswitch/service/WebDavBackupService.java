package com.github.mostbean.codingswitch.service;

import com.github.mostbean.codingswitch.model.McpServer;
import com.github.mostbean.codingswitch.model.PromptPreset;
import com.github.mostbean.codingswitch.model.Provider;
import com.github.mostbean.codingswitch.model.Skill;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.Credentials;
import com.intellij.ide.passwordSafe.PasswordSafe;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * WebDAV 备份同步服务：将插件全部配置（Provider/MCP/Skills/Prompts/偏好设置）
 * 打包成快照并上传到 WebDAV 存储，或从远端恢复。
 */
@Service(Service.Level.APP)
public final class WebDavBackupService {

    private static final Logger LOG = Logger.getInstance(WebDavBackupService.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int TIMEOUT_SECONDS = 30;
    private static final String BACKUP_VERSION = "1.0";
    private static final String CREDENTIAL_KEY_PASSWORD = "codingswitch.webdav.password";
    private static final String CREDENTIAL_KEY_PASSPHRASE = "codingswitch.webdav.passphrase";

    public static WebDavBackupService getInstance() {
        return ApplicationManager.getApplication().getService(WebDavBackupService.class);
    }

    /**
     * 测试 WebDAV 连接。
     */
    public TestResult testConnection(String baseUrl, String username, String password) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return new TestResult(false, I18n.t("settings.backup.validation.urlRequired"));
        }
        try {
            HttpClient client = createHttpClient();
            String normalizedUrl = normalizeUrl(baseUrl);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(normalizedUrl))
                    .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                    .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                    .header("Authorization", basicAuth(username, password))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            if (status >= 200 && status < 400) {
                return new TestResult(true, I18n.t("settings.backup.test.success"));
            }
            return new TestResult(false, I18n.t("settings.backup.test.failed", "HTTP " + status));
        } catch (Exception e) {
            LOG.warn("WebDAV connection test failed", e);
            return new TestResult(false, I18n.t("settings.backup.test.failed", safeMessage(e)));
        }
    }

    /**
     * 立即备份：组装快照 → 可选加密 → 上传到 WebDAV。
     */
    public UploadResult uploadSnapshot(String baseUrl, String username, String password,
                                       String remotePath, boolean encrypt, String passphrase) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return new UploadResult(false, I18n.t("settings.backup.validation.urlRequired"));
        }
        try {
            BackupSnapshot snapshot = assembleSnapshot(encrypt);
            String json = GSON.toJson(snapshot);
            byte[] content;
            if (encrypt) {
                // 口令为空时回退使用 WebDAV 密码作为加密密钥
                String key = resolveEncryptionKey(passphrase, password);
                if (key.isBlank()) {
                    return new UploadResult(false, I18n.t("settings.backup.validation.encryptKeyRequired"));
                }
                content = BackupCrypto.encrypt(json, key);
            } else {
                content = json.getBytes(StandardCharsets.UTF_8);
            }

            HttpClient client = createHttpClient();
            String fullUrl = buildFullUrl(baseUrl, remotePath);
            ensureParentDirectories(client, baseUrl, username, password, remotePath);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(fullUrl))
                    .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                    .PUT(HttpRequest.BodyPublishers.ofByteArray(content))
                    .header("Authorization", basicAuth(username, password))
                    .header("Content-Type", "application/octet-stream")
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                return new UploadResult(true, I18n.t("settings.backup.upload.success"));
            }
            return new UploadResult(false, I18n.t("settings.backup.upload.failed", "HTTP " + status));
        } catch (Exception e) {
            LOG.warn("WebDAV upload failed", e);
            return new UploadResult(false, I18n.t("settings.backup.upload.failed", safeMessage(e)));
        }
    }

    /**
     * 解析加密密钥：优先使用备份口令，为空时回退使用 WebDAV 密码。
     */
    private String resolveEncryptionKey(String passphrase, String webdavPassword) {
        if (passphrase != null && !passphrase.isBlank()) {
            return passphrase;
        }
        return webdavPassword == null ? "" : webdavPassword;
    }

    /**
     * 从远端恢复：下载快照 → 自动检测加密 → 可选解密 → 写回本地。
     */
    public RestoreResult downloadSnapshot(String baseUrl, String username, String password,
                                          String remotePath, boolean userExpectsEncrypted, String passphrase) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return new RestoreResult(false, null, I18n.t("settings.backup.validation.urlRequired"));
        }
        try {
            HttpClient client = createHttpClient();
            String fullUrl = buildFullUrl(baseUrl, remotePath);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(fullUrl))
                    .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                    .GET()
                    .header("Authorization", basicAuth(username, password))
                    .build();
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            int status = response.statusCode();
            if (status == 404) {
                return new RestoreResult(false, null, I18n.t("settings.backup.restore.empty"));
            }
            if (status < 200 || status >= 300) {
                return new RestoreResult(false, null, I18n.t("settings.backup.restore.failed", "HTTP " + status));
            }

            byte[] content = response.body();
            String json;

            // 先尝试按明文解析，判断云端是否加密
            String rawText = new String(content, StandardCharsets.UTF_8).trim();
            boolean cloudIsEncrypted = !rawText.startsWith("{");  // 明文快照以 '{' 开头

            if (cloudIsEncrypted) {
                // 云端是加密的，需要解密
                String key = resolveEncryptionKey(passphrase, password);
                if (key.isBlank()) {
                    return new RestoreResult(false, null,
                        I18n.t("settings.backup.restore.encryptedButNoKey"));
                }
                try {
                    json = BackupCrypto.decrypt(content, key);
                } catch (Exception decryptEx) {
                    LOG.warn("Decryption failed", decryptEx);
                    return new RestoreResult(false, null,
                        I18n.t("settings.backup.restore.decryptFailed") +
                        "\n" + I18n.t("settings.backup.restore.wrongPassword"));
                }
            } else {
                // 云端是明文的
                if (userExpectsEncrypted) {
                    // 用户勾选了加密但云端是明文，给出提示（不阻止恢复）
                    LOG.info("User expects encrypted snapshot but cloud snapshot is plaintext, proceeding anyway.");
                }
                json = rawText;
            }

            BackupSnapshot snapshot = GSON.fromJson(json, BackupSnapshot.class);
            return new RestoreResult(true, snapshot, null);
        } catch (Exception e) {
            LOG.warn("WebDAV download failed", e);
            return new RestoreResult(false, null, I18n.t("settings.backup.restore.failed", safeMessage(e)));
        }
    }

    /**
     * 应用快照到本地（覆盖所有配置）。
     */
    public void applySnapshot(BackupSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        PluginSettings.DataStorageMode storageMode = PluginSettings.getInstance().getStorageMode();
        boolean isSharedMode = storageMode == PluginSettings.DataStorageMode.USER_SHARED;

        // Providers
        if (snapshot.providers != null) {
            ProviderService providerService = ProviderService.getInstance();
            ProviderService.State providerState = new ProviderService.State();
            providerState.providersJson = GSON.toJson(snapshot.providers);
            if (isSharedMode) {
                providerService.writeSharedState(providerState);
            } else {
                providerService.overwriteLocalState(providerState);
            }
            providerService.notifyStateChanged();
        }
        // MCP
        if (snapshot.mcpServers != null) {
            McpService mcpService = McpService.getInstance();
            McpService.StateData mcpState = new McpService.StateData();
            mcpState.serversJson = GSON.toJson(snapshot.mcpServers);
            if (isSharedMode) {
                mcpService.writeSharedState(mcpState);
            } else {
                mcpService.overwriteLocalState(mcpState);
            }
            mcpService.notifyStateChanged();
        }
        // Skills
        if (snapshot.skills != null) {
            SkillService skillService = SkillService.getInstance();
            SkillService.State skillState = new SkillService.State();
            skillState.skillsJson = GSON.toJson(snapshot.skills);
            if (isSharedMode) {
                skillService.writeSharedState(skillState);
            } else {
                skillService.overwriteLocalState(skillState);
            }
            skillService.notifyStateChanged();
        }
        // Prompts
        if (snapshot.prompts != null) {
            PromptService promptService = PromptService.getInstance();
            PromptService.State promptState = new PromptService.State();
            promptState.presetsJson = GSON.toJson(snapshot.prompts);
            if (isSharedMode) {
                promptService.writeSharedState(promptState);
            } else {
                promptService.overwriteLocalState(promptState);
            }
            promptService.notifyStateChanged();
        }
        // AI Feature Settings
        if (snapshot.aiFeatureSettings != null) {
            AiFeatureSettings aiFeatureSettings = AiFeatureSettings.getInstance();
            if (isSharedMode) {
                aiFeatureSettings.writeSharedState(snapshot.aiFeatureSettings);
            } else {
                aiFeatureSettings.overwriteLocalState(snapshot.aiFeatureSettings);
            }
        }
        // Plugin Settings：恢复共享偏好，但保留本设备的"设备本地属性"，避免破坏当前环境。
        if (snapshot.pluginSettings != null) {
            PluginSettings currentSettings = PluginSettings.getInstance();
            PluginSettings.State restored = snapshot.pluginSettings;

            // 关键：storageMode 是设备本地属性（getStorageMode 永远读本地 state.storageMode）。
            // 若用快照里源设备的 storageMode 覆盖，会导致本设备存储模式被篡改：
            // 例如源设备为 USER_SHARED、本设备为 IDE_LOCAL，恢复后本设备误判为 USER_SHARED，
            // 而配置刚写入的是本地文件，读取却转向共享文件 → 配置再次"丢失"。因此必须保留当前值。
            restored.storageMode = currentSettings.getStorageMode().name();

            // ccSwitchConfigDirectory 是本机文件系统路径，跨设备无意义，保留当前值。
            restored.ccSwitchConfigDirectory = currentSettings.getCcSwitchConfigDirectory();

            // WebDAV 备份配置也保留当前值，避免恢复后无法继续备份。
            restored.webdavBackupUrl = currentSettings.getWebdavBackupUrl();
            restored.webdavBackupUsername = currentSettings.getWebdavBackupUsername();
            restored.webdavRemotePath = currentSettings.getWebdavRemotePath();
            restored.webdavRememberPassword = currentSettings.isWebdavRememberPassword();
            restored.webdavAutoUpload = currentSettings.isWebdavAutoUpload();
            restored.webdavAutoUploadIntervalMinutes = currentSettings.getWebdavAutoUploadIntervalMinutes();
            restored.webdavEncryptSensitive = currentSettings.isWebdavEncryptSensitive();
            if (isSharedMode) {
                currentSettings.writeSharedState(restored);
            } else {
                currentSettings.overwriteLocalState(restored);
            }
        }
    }

    /**
     * 组装快照：从各服务读取当前配置。
     */
    private BackupSnapshot assembleSnapshot(boolean includeEncrypted) {
        BackupSnapshot snapshot = new BackupSnapshot();
        snapshot.version = BACKUP_VERSION;
        snapshot.createdAt = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        snapshot.sourceHost = getHostIdentifier();
        snapshot.encrypted = includeEncrypted;

        snapshot.providers = new ArrayList<>(ProviderService.getInstance().getProviders());
        snapshot.mcpServers = new ArrayList<>(McpService.getInstance().getServers());
        snapshot.skills = new ArrayList<>(SkillService.getInstance().getSkills());
        snapshot.prompts = new ArrayList<>(PromptService.getInstance().getPresets());
        // 使用 snapshotCurrentState()：它通过 getActiveState() 读取（在 USER_SHARED
        // 模式下读共享文件，含 profiles），并回填遗留在 PasswordSafe 中的 API Key。
        // 不能用 getState()（仅返回本地 XML 内存态，共享模式下可能为空）。
        snapshot.aiFeatureSettings = AiFeatureSettings.getInstance().snapshotCurrentState();
        snapshot.pluginSettings = PluginSettings.getInstance().snapshotCurrentState();

        return snapshot;
    }

    private void ensureParentDirectories(HttpClient client, String baseUrl, String username, String password, String remotePath) throws IOException, InterruptedException {
        String normalized = normalizeUrl(baseUrl);
        String[] segments = remotePath.split("/");
        String currentPath = normalized;
        for (int i = 0; i < segments.length - 1; i++) {
            if (segments[i].isBlank()) {
                continue;
            }
            currentPath = currentPath.endsWith("/") ? currentPath + segments[i] : currentPath + "/" + segments[i];
            try {
                HttpRequest mkcolRequest = HttpRequest.newBuilder()
                        .uri(URI.create(currentPath))
                        .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                        .method("MKCOL", HttpRequest.BodyPublishers.noBody())
                        .header("Authorization", basicAuth(username, password))
                        .build();
                client.send(mkcolRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (Exception ignored) {
                // Directory may already exist, continue
            }
        }
    }

    private HttpClient createHttpClient() {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .build();
    }

    private String normalizeUrl(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        String normalized = url.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String buildFullUrl(String baseUrl, String remotePath) {
        String base = normalizeUrl(baseUrl);
        String path = remotePath == null ? "" : remotePath.trim();
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        return base + "/" + path;
    }

    private String basicAuth(String username, String password) {
        String credentials = (username == null ? "" : username) + ":" + (password == null ? "" : password);
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    private String getHostIdentifier() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown-host";
        }
    }

    private String safeMessage(Throwable throwable) {
        if (throwable == null) {
            return "";
        }
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // Password Safe Integration
    // ────────────────────────────────────────────────────────────────────────────────

    public void saveWebDavPassword(String password) {
        if (password == null || password.isBlank()) {
            PasswordSafe.getInstance().set(new CredentialAttributes(CREDENTIAL_KEY_PASSWORD), null);
            return;
        }
        PasswordSafe.getInstance().set(
                new CredentialAttributes(CREDENTIAL_KEY_PASSWORD),
                new Credentials(CREDENTIAL_KEY_PASSWORD, password)
        );
    }

    public String loadWebDavPassword() {
        Credentials credentials = PasswordSafe.getInstance().get(new CredentialAttributes(CREDENTIAL_KEY_PASSWORD));
        return credentials == null ? "" : (credentials.getPasswordAsString() == null ? "" : credentials.getPasswordAsString());
    }

    public void saveBackupPassphrase(String passphrase) {
        if (passphrase == null || passphrase.isBlank()) {
            PasswordSafe.getInstance().set(new CredentialAttributes(CREDENTIAL_KEY_PASSPHRASE), null);
            return;
        }
        PasswordSafe.getInstance().set(
                new CredentialAttributes(CREDENTIAL_KEY_PASSPHRASE),
                new Credentials(CREDENTIAL_KEY_PASSPHRASE, passphrase)
        );
    }

    public String loadBackupPassphrase() {
        Credentials credentials = PasswordSafe.getInstance().get(new CredentialAttributes(CREDENTIAL_KEY_PASSPHRASE));
        return credentials == null ? "" : (credentials.getPasswordAsString() == null ? "" : credentials.getPasswordAsString());
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // DTOs
    // ────────────────────────────────────────────────────────────────────────────────

    public record TestResult(boolean success, String message) {}
    public record UploadResult(boolean success, String message) {}
    public record RestoreResult(boolean success, BackupSnapshot snapshot, String errorMessage) {}

    public static class BackupSnapshot {
        public String version;
        public String createdAt;
        public String sourceHost;
        public boolean encrypted;
        public List<Provider> providers;
        public List<McpServer> mcpServers;
        public List<Skill> skills;
        public List<PromptPreset> prompts;
        public AiFeatureSettings.State aiFeatureSettings;
        public PluginSettings.State pluginSettings;
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // AES-GCM Encryption (JDK built-in)
    // ────────────────────────────────────────────────────────────────────────────────

    private static class BackupCrypto {
        private static final int GCM_TAG_LENGTH = 128;
        private static final int GCM_IV_LENGTH = 12;
        private static final int PBKDF2_ITERATIONS = 100000;
        private static final int KEY_LENGTH = 256;

        static byte[] encrypt(String plaintext, String passphrase) throws Exception {
            byte[] salt = new byte[16];
            new SecureRandom().nextBytes(salt);
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            SecretKey key = deriveKey(passphrase, salt);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[salt.length + iv.length + ciphertext.length];
            System.arraycopy(salt, 0, combined, 0, salt.length);
            System.arraycopy(iv, 0, combined, salt.length, iv.length);
            System.arraycopy(ciphertext, 0, combined, salt.length + iv.length, ciphertext.length);
            return combined;
        }

        static String decrypt(byte[] combined, String passphrase) throws Exception {
            byte[] salt = new byte[16];
            byte[] iv = new byte[GCM_IV_LENGTH];
            byte[] ciphertext = new byte[combined.length - salt.length - iv.length];

            System.arraycopy(combined, 0, salt, 0, salt.length);
            System.arraycopy(combined, salt.length, iv, 0, iv.length);
            System.arraycopy(combined, salt.length + iv.length, ciphertext, 0, ciphertext.length);

            SecretKey key = deriveKey(passphrase, salt);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] plaintext = cipher.doFinal(ciphertext);
            return new String(plaintext, StandardCharsets.UTF_8);
        }

        private static SecretKey deriveKey(String passphrase, byte[] salt) throws Exception {
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            PBEKeySpec spec = new PBEKeySpec(passphrase.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH);
            byte[] keyBytes = factory.generateSecret(spec).getEncoded();
            return new SecretKeySpec(keyBytes, "AES");
        }
    }
}
