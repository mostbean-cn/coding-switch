package com.github.mostbean.codingswitch.service;

import com.github.mostbean.codingswitch.model.CliType;
import com.github.mostbean.codingswitch.model.Provider;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteOpenMode;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

@Service(Service.Level.APP)
public final class CcSwitchSyncService {

    private static final Logger LOG = Logger.getInstance(CcSwitchSyncService.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String STATUS_ALL = "all";
    private static final String STATUS_SYNCED = "synced";
    private static final String STATUS_UNSYNCED = "unsynced";

    public enum SyncDirection {
        TO_CC_SWITCH,
        TO_CODING_SWITCH
    }

    public enum SyncScope {
        SAVED_PROVIDER
    }

    public record SyncItem(
            String sourceId,
            CliType cliType,
            String name,
            SyncScope scope,
            SyncDirection direction,
            boolean synced) {
    }

    public record SyncResult(int successCount, int failureCount, List<String> details) {
        public String message() {
            if (details == null || details.isEmpty()) {
                return "";
            }
            return String.join("\n", details);
        }
    }

    private record RemoteProviderRecord(
            String id,
            CliType cliType,
            String name,
            JsonObject settingsConfig,
            long createdAt,
            long sortIndex,
            String metaJson) {
    }

    private record LocalProviderRecord(
            String id,
            CliType cliType,
            String name,
            JsonObject settingsConfig) {
    }

    public static CcSwitchSyncService getInstance() {
        return ApplicationManager.getApplication().getService(CcSwitchSyncService.class);
    }

    public boolean isCcSwitchAvailable() {
        return Files.exists(getCcSwitchDbPath());
    }

    public List<SyncItem> loadSyncItems() {
        if (!isCcSwitchAvailable()) {
            return List.of();
        }

        List<LocalProviderRecord> localProviders = loadLocalProviders();
        List<RemoteProviderRecord> remoteProviders = loadRemoteProviders();
        List<SyncItem> items = new ArrayList<>();

        for (LocalProviderRecord local : localProviders) {
            RemoteProviderRecord remote = findRemoteProviderMatch(remoteProviders, local);
            items.add(new SyncItem(
                    local.id(),
                    local.cliType(),
                    local.name(),
                    SyncScope.SAVED_PROVIDER,
                    SyncDirection.TO_CC_SWITCH,
                    remote != null));
        }

        for (RemoteProviderRecord remote : remoteProviders) {
            LocalProviderRecord local = findLocalProviderMatch(localProviders, remote);
            items.add(new SyncItem(
                    remote.id(),
                    remote.cliType(),
                    remote.name(),
                    SyncScope.SAVED_PROVIDER,
                    SyncDirection.TO_CODING_SWITCH,
                    local != null));
        }

        items.sort(Comparator
                .comparing((SyncItem item) -> item.cliType().getDisplayName(), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(SyncItem::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(item -> item.direction() == SyncDirection.TO_CC_SWITCH ? 0 : 1));
        return items;
    }

    public List<SyncItem> filterItems(List<SyncItem> items, @Nullable CliType cliFilter, @Nullable String statusFilter) {
        String normalizedStatus = normalizeStatusFilter(statusFilter);
        List<SyncItem> filtered = new ArrayList<>();
        for (SyncItem item : items) {
            if (cliFilter != null && item.cliType() != cliFilter) {
                continue;
            }
            if (STATUS_SYNCED.equals(normalizedStatus) && !item.synced()) {
                continue;
            }
            if (STATUS_UNSYNCED.equals(normalizedStatus) && item.synced()) {
                continue;
            }
            filtered.add(item);
        }
        return filtered;
    }

    public SyncResult syncItems(List<SyncItem> items) {
        return syncItems(items, false);
    }

    public SyncResult syncItems(List<SyncItem> items, boolean enableCcSwitchCommonConfig) {
        int success = 0;
        List<String> failures = new ArrayList<>();
        for (SyncItem item : items) {
            try {
                syncItem(item, enableCcSwitchCommonConfig);
                success++;
            } catch (Exception e) {
                LOG.warn("Failed to sync item: " + item.name() + " / " + item.cliType(), e);
                failures.add(item.name() + " (" + item.cliType().getDisplayName() + "): " + safeMessage(e));
            }
        }
        return new SyncResult(success, failures.size(), failures);
    }

    public void syncItem(SyncItem item) throws IOException, SQLException {
        syncItem(item, false);
    }

    private void syncItem(SyncItem item, boolean enableCcSwitchCommonConfig) throws IOException, SQLException {
        requireCcSwitchAvailable();
        if (item.scope() != SyncScope.SAVED_PROVIDER) {
            throw new IOException("Unsupported sync scope: " + item.scope());
        }
        if (item.direction() == SyncDirection.TO_CC_SWITCH) {
            syncLocalProviderToCcSwitch(item.sourceId(), enableCcSwitchCommonConfig);
        } else {
            importCcSwitchProvider(item.sourceId());
        }
    }

    public String normalizeStatusFilter(@Nullable String statusFilter) {
        if (STATUS_SYNCED.equals(statusFilter) || STATUS_UNSYNCED.equals(statusFilter)) {
            return statusFilter;
        }
        return STATUS_ALL;
    }

    public String scopeDisplayName(SyncScope scope) {
        return I18n.t("settings.sync.scope.saved");
    }

    public int countExistingExportTargets(List<SyncItem> items) {
        if (items == null || items.isEmpty() || !isCcSwitchAvailable()) {
            return 0;
        }
        List<LocalProviderRecord> localProviders = loadLocalProviders();
        List<RemoteProviderRecord> remoteProviders = loadRemoteProviders();
        int count = 0;
        for (SyncItem item : items) {
            if (item.direction() != SyncDirection.TO_CC_SWITCH || item.scope() != SyncScope.SAVED_PROVIDER) {
                continue;
            }
            LocalProviderRecord local = localProviders.stream()
                    .filter(source -> Objects.equals(item.sourceId(), source.id()))
                    .findFirst()
                    .orElse(null);
            if (local != null && findRemoteProviderMatch(remoteProviders, local) != null) {
                count++;
            }
        }
        return count;
    }

    public int countExistingImportTargets(List<SyncItem> items) {
        if (items == null || items.isEmpty() || !isCcSwitchAvailable()) {
            return 0;
        }
        List<LocalProviderRecord> localProviders = loadLocalProviders();
        List<RemoteProviderRecord> remoteProviders = loadRemoteProviders();
        int count = 0;
        for (SyncItem item : items) {
            if (item.direction() != SyncDirection.TO_CODING_SWITCH || item.scope() != SyncScope.SAVED_PROVIDER) {
                continue;
            }
            RemoteProviderRecord remote = remoteProviders.stream()
                    .filter(source -> Objects.equals(item.sourceId(), source.id()))
                    .findFirst()
                    .orElse(null);
            if (remote != null && findLocalProviderMatch(localProviders, remote) != null) {
                count++;
            }
        }
        return count;
    }

    public JsonObject loadSourceSettingsConfig(SyncItem item) throws IOException, SQLException {
        if (item.scope() != SyncScope.SAVED_PROVIDER) {
            throw new IOException("Unsupported sync scope: " + item.scope());
        }
        if (item.direction() == SyncDirection.TO_CC_SWITCH) {
            return loadLocalProviders().stream()
                    .filter(source -> Objects.equals(item.sourceId(), source.id()))
                    .findFirst()
                    .map(record -> record.settingsConfig().deepCopy())
                    .orElseThrow(() -> new IOException("Local provider not found: " + item.sourceId()));
        }

        try (Connection connection = openCcSwitchConnection()) {
            ensureCcSwitchSchema(connection);
            RemoteProviderRecord remote = findRemoteProviderById(connection, item.sourceId());
            if (remote == null) {
                throw new SQLException("Remote provider not found: " + item.sourceId());
            }
            return remote.settingsConfig().deepCopy();
        }
    }

    private void syncLocalProviderToCcSwitch(String providerId, boolean enableCcSwitchCommonConfig) throws IOException, SQLException {
        LocalProviderRecord local = loadLocalProviders().stream()
                .filter(source -> Objects.equals(providerId, source.id()))
                .findFirst()
                .orElseThrow(() -> new IOException("Local provider not found: " + providerId));

        try (Connection connection = openCcSwitchConnection()) {
            ensureCcSwitchSchema(connection);
            RemoteProviderRecord existing = findRemoteProviderByIdOrName(connection, local.cliType(), local.id(), local.name());
            long createdAt = existing != null && existing.createdAt() > 0 ? existing.createdAt() : System.currentTimeMillis();
            long sortIndex = existing != null ? existing.sortIndex() : nextSortIndex(connection, local.cliType());
            String targetId = existing != null ? existing.id() : local.id();
            String metaJson = existing != null ? existing.metaJson() : "{}";
            if (enableCcSwitchCommonConfig) {
                metaJson = withCommonConfigEnabled(metaJson);
            }

            upsertRemoteProvider(connection, targetId, local, createdAt, sortIndex, metaJson);
        }
    }

    private void importCcSwitchProvider(String remoteId) throws SQLException, IOException {
        RemoteProviderRecord remote;
        try (Connection connection = openCcSwitchConnection()) {
            ensureCcSwitchSchema(connection);
            remote = findRemoteProviderById(connection, remoteId);
        }
        if (remote == null) {
            throw new SQLException("Remote provider not found: " + remoteId);
        }

        ProviderService providerService = ProviderService.getInstance();
        Provider existing = providerService.getProviders().stream()
                .filter(p -> isSupported(p.getCliType()))
                .filter(p -> Objects.equals(remote.id(), p.getId())
                        || (p.getCliType() == remote.cliType() && p.getName().equalsIgnoreCase(remote.name())))
                .findFirst()
                .orElse(null);

        if (existing != null) {
            replaceExistingProviderConfig(providerService, existing, remote);
            return;
        }

        Provider provider = new Provider(remote.cliType(), uniqueImportedProviderName(remote.name()));
        provider.setId(remote.id());
        provider.setSettingsConfig(remote.settingsConfig().deepCopy());
        provider.setAuthMode(Provider.inferAuthMode(remote.cliType(), remote.settingsConfig()));
        provider.setActive(false);
        provider.setPendingActivation(false);
        providerService.addProvider(provider);
    }

    private void replaceExistingProviderConfig(
            ProviderService providerService,
            Provider existing,
            RemoteProviderRecord remote) {
        Provider updated = new Provider(remote.cliType(), remote.name());
        updated.setId(existing.getId());
        updated.setSettingsConfig(remote.settingsConfig().deepCopy());
        updated.setAuthMode(Provider.inferAuthMode(remote.cliType(), remote.settingsConfig()));
        updated.setAuthBindingKey(existing.getEffectiveAuthBindingKey());
        updated.setCreatedAt(existing.getCreatedAt());
        updated.setActive(existing.isActive());
        updated.setPendingActivation(false);

        List<Provider> providers = new ArrayList<>(providerService.getProviders());
        providers.replaceAll(provider -> Objects.equals(provider.getId(), existing.getId()) ? updated : provider);
        ProviderService.State state = new ProviderService.State();
        state.providersJson = GSON.toJson(providers);
        if (PluginSettings.getInstance().getStorageMode() == PluginSettings.DataStorageMode.USER_SHARED) {
            providerService.writeSharedState(state);
        } else {
            providerService.overwriteLocalState(state);
        }
        providerService.notifyStateChanged();
    }

    private List<LocalProviderRecord> loadLocalProviders() {
        List<LocalProviderRecord> localProviders = new ArrayList<>();
        for (Provider provider : ProviderService.getInstance().getProviders().stream()
                .filter(provider -> isSupported(provider.getCliType()))
                .filter(provider -> provider.getAuthMode() != Provider.AuthMode.OFFICIAL_LOGIN)
                .filter(provider -> !isEmptyProviderConfig(provider.getCliType(), provider.getSettingsConfig()))
                .toList()) {
            localProviders.add(new LocalProviderRecord(
                    provider.getId(),
                    provider.getCliType(),
                    provider.getName(),
                    provider.getSettingsConfig().deepCopy()));
        }
        return localProviders;
    }

    private List<RemoteProviderRecord> loadRemoteProviders() {
        Path dbPath = getCcSwitchDbPath();
        if (!Files.exists(dbPath)) {
            return List.of();
        }

        List<RemoteProviderRecord> result = new ArrayList<>();
        try (Connection connection = openCcSwitchConnection()) {
            ensureCcSwitchSchema(connection);
            String sql = "select id, app_type, name, settings_config, created_at, sort_index, meta from providers";
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery(sql)) {
                while (rs.next()) {
                    CliType cliType = mapRemoteCliType(rs.getString("app_type"));
                    if (!isSupported(cliType)) {
                        continue;
                    }
                    JsonObject config = parseJsonObject(rs.getString("settings_config"));
                    if (config == null || isEmptyProviderConfig(cliType, config)) {
                        continue;
                    }
                    if (Provider.inferAuthMode(cliType, config) == Provider.AuthMode.OFFICIAL_LOGIN) {
                        continue;
                    }
                    result.add(new RemoteProviderRecord(
                            rs.getString("id"),
                            cliType,
                            rs.getString("name"),
                            config,
                            rs.getLong("created_at"),
                            rs.getLong("sort_index"),
                            nullableText(rs.getString("meta"), "{}")));
                }
            }
        } catch (Exception e) {
            LOG.warn("Failed to load cc-switch providers", e);
        }
        return result;
    }

    private @Nullable RemoteProviderRecord findRemoteProviderMatch(List<RemoteProviderRecord> remoteProviders, LocalProviderRecord local) {
        RemoteProviderRecord byId = remoteProviders.stream()
                .filter(remote -> local.cliType() == remote.cliType())
                .filter(remote -> Objects.equals(local.id(), remote.id()))
                .findFirst()
                .orElse(null);
        if (byId != null) {
            return byId;
        }
        return remoteProviders.stream()
                .filter(remote -> local.cliType() == remote.cliType())
                .filter(remote -> remote.name().equalsIgnoreCase(local.name()))
                .findFirst()
                .orElse(null);
    }

    private @Nullable LocalProviderRecord findLocalProviderMatch(List<LocalProviderRecord> localProviders, RemoteProviderRecord remote) {
        LocalProviderRecord byId = localProviders.stream()
                .filter(local -> remote.cliType() == local.cliType())
                .filter(local -> Objects.equals(remote.id(), local.id()))
                .findFirst()
                .orElse(null);
        if (byId != null) {
            return byId;
        }
        return localProviders.stream()
                .filter(local -> remote.cliType() == local.cliType())
                .filter(local -> local.name().equalsIgnoreCase(remote.name()))
                .findFirst()
                .orElse(null);
    }

    private Connection openCcSwitchConnection() throws SQLException, IOException {
        requireCcSwitchAvailable();
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new IOException("SQLite JDBC driver unavailable", e);
        }
        String url = "jdbc:sqlite:" + getCcSwitchDbPath().toAbsolutePath();
        SQLiteConfig config = new SQLiteConfig();
        config.setOpenMode(SQLiteOpenMode.READWRITE);
        config.resetOpenMode(SQLiteOpenMode.CREATE);
        Connection connection = DriverManager.getConnection(url, config.toProperties());
        connection.setAutoCommit(true);
        return connection;
    }

    private void ensureCcSwitchSchema(Connection connection) throws SQLException, IOException {
        if (!hasTable(connection, "providers") || !hasTable(connection, "settings")) {
            throw new IOException("cc-switch 数据库结构不完整");
        }
    }

    private @Nullable RemoteProviderRecord findRemoteProviderById(Connection connection, String id) throws SQLException {
        String sql = "select id, app_type, name, settings_config, created_at, sort_index, meta from providers where id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                CliType cliType = mapRemoteCliType(rs.getString("app_type"));
                if (!isSupported(cliType)) {
                    return null;
                }
                JsonObject config = parseJsonObject(rs.getString("settings_config"));
                if (config == null) {
                    return null;
                }
                return new RemoteProviderRecord(
                        rs.getString("id"),
                        cliType,
                        rs.getString("name"),
                        config,
                        rs.getLong("created_at"),
                        rs.getLong("sort_index"),
                        nullableText(rs.getString("meta"), "{}"));
            }
        }
    }

    private @Nullable RemoteProviderRecord findRemoteProviderByIdOrName(
            Connection connection,
            CliType cliType,
            String id,
            String name) throws SQLException {
        RemoteProviderRecord byId = findRemoteProviderById(connection, id);
        if (byId != null && byId.cliType() == cliType) {
            return byId;
        }

        String sql = "select id, app_type, name, settings_config, created_at, sort_index, meta "
                + "from providers where app_type = ? and lower(name) = lower(?) order by created_at asc limit 1";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, toRemoteAppType(cliType));
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                JsonObject config = parseJsonObject(rs.getString("settings_config"));
                if (config == null) {
                    return null;
                }
                return new RemoteProviderRecord(
                        rs.getString("id"),
                        cliType,
                        rs.getString("name"),
                        config,
                        rs.getLong("created_at"),
                        rs.getLong("sort_index"),
                        nullableText(rs.getString("meta"), "{}"));
            }
        }
    }

    private long nextSortIndex(Connection connection, CliType cliType) throws SQLException {
        String sql = "select coalesce(max(sort_index), 0) from providers where app_type = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, toRemoteAppType(cliType));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) + 1 : 1;
            }
        }
    }

    private void upsertRemoteProvider(
            Connection connection,
            String id,
            LocalProviderRecord provider,
            long createdAt,
            long sortIndex,
            String metaJson) throws SQLException {
        String appType = toRemoteAppType(provider.cliType());
        String configJson = GSON.toJson(provider.settingsConfig());
        String safeMeta = nullableText(metaJson, "{}");

        String updateSql = """
                update providers
                set name = ?,
                    settings_config = ?,
                    created_at = coalesce(created_at, ?),
                    sort_index = ?,
                    meta = ?
                where id = ? and app_type = ?
                """;
        try (PreparedStatement ps = connection.prepareStatement(updateSql)) {
            ps.setString(1, provider.name());
            ps.setString(2, configJson);
            ps.setLong(3, createdAt > 0 ? createdAt : Instant.now().toEpochMilli());
            ps.setLong(4, sortIndex);
            ps.setString(5, safeMeta);
            ps.setString(6, id);
            ps.setString(7, appType);
            if (ps.executeUpdate() > 0) {
                return;
            }
        }

        String insertSql = """
                insert into providers (
                    id, app_type, name, settings_config, created_at, sort_index, meta, is_current
                ) values (?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = connection.prepareStatement(insertSql)) {
            ps.setString(1, id);
            ps.setString(2, appType);
            ps.setString(3, provider.name());
            ps.setString(4, configJson);
            ps.setLong(5, createdAt > 0 ? createdAt : Instant.now().toEpochMilli());
            ps.setLong(6, sortIndex);
            ps.setString(7, safeMeta);
            ps.setBoolean(8, false);
            ps.executeUpdate();
        }
    }

    private boolean hasTable(Connection connection, String tableName) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "select name from sqlite_master where type = 'table' and name = ?")) {
            ps.setString(1, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private void requireCcSwitchAvailable() throws IOException {
        if (!isCcSwitchAvailable()) {
            throw new IOException("未检测到 cc-switch，请先安装并初始化后再同步");
        }
    }

    private @Nullable JsonObject parseJsonObject(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return JsonParser.parseString(raw).getAsJsonObject();
        } catch (Exception e) {
            LOG.warn("Failed to parse JSON object", e);
            return null;
        }
    }

    private String withCommonConfigEnabled(String metaJson) {
        JsonObject meta = parseJsonObject(nullableText(metaJson, "{}"));
        if (meta == null) {
            meta = new JsonObject();
        }
        meta.addProperty("commonConfigEnabled", true);
        return GSON.toJson(meta);
    }

    private @Nullable CliType mapRemoteCliType(@Nullable String appType) {
        if (appType == null || appType.isBlank()) {
            return null;
        }
        return switch (appType.toLowerCase(Locale.ROOT)) {
            case "claude" -> CliType.CLAUDE;
            case "codex" -> CliType.CODEX;
            case "opencode" -> CliType.OPENCODE;
            default -> null;
        };
    }

    private String toRemoteAppType(@NotNull CliType cliType) {
        return switch (cliType) {
            case CLAUDE -> "claude";
            case CODEX -> "codex";
            case OPENCODE -> "opencode";
            default -> throw new IllegalArgumentException("Unsupported cli type: " + cliType);
        };
    }

    private String normalizeToml(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String normalized = raw.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);
        List<String> cleaned = new ArrayList<>();
        for (String line : lines) {
            cleaned.add(trimRight(line));
        }
        while (!cleaned.isEmpty() && cleaned.get(cleaned.size() - 1).isBlank()) {
            cleaned.remove(cleaned.size() - 1);
        }
        return String.join("\n", cleaned).trim();
    }

    private String trimRight(String value) {
        int end = value.length();
        while (end > 0 && Character.isWhitespace(value.charAt(end - 1)) && value.charAt(end - 1) != '\n') {
            end--;
        }
        return value.substring(0, end);
    }

    private boolean isSupported(@Nullable CliType cliType) {
        return cliType == CliType.CLAUDE || cliType == CliType.CODEX || cliType == CliType.OPENCODE;
    }

    private boolean isEmptyProviderConfig(CliType cliType, JsonObject config) {
        if (config == null || config.keySet().isEmpty()) {
            return true;
        }
        return switch (cliType) {
            case CLAUDE -> {
                JsonObject env = config.has("env") && config.get("env").isJsonObject()
                        ? config.getAsJsonObject("env")
                        : null;
                yield env == null || env.keySet().isEmpty();
            }
            case CODEX -> {
                JsonObject auth = config.has("auth") && config.get("auth").isJsonObject()
                        ? config.getAsJsonObject("auth")
                        : null;
                String toml = config.has("config") && !config.get("config").isJsonNull()
                        ? normalizeToml(config.get("config").getAsString())
                        : "";
                boolean emptyAuth = auth == null || auth.keySet().isEmpty();
                yield emptyAuth && toml.isBlank();
            }
            case OPENCODE -> {
                JsonObject options = config.has("options") && config.get("options").isJsonObject()
                        ? config.getAsJsonObject("options")
                        : null;
                JsonObject models = config.has("models") && config.get("models").isJsonObject()
                        ? config.getAsJsonObject("models")
                        : null;
                String npm = config.has("npm") && !config.get("npm").isJsonNull()
                        ? config.get("npm").getAsString().trim()
                        : "";
                boolean emptyOptions = options == null || options.keySet().isEmpty();
                boolean emptyModels = models == null || models.keySet().isEmpty();
                yield emptyOptions && emptyModels && npm.isBlank();
            }
            default -> true;
        };
    }

    private Path getCcSwitchRootDir() {
        return Path.of(System.getProperty("user.home"), ".cc-switch");
    }

    private Path getCcSwitchDbPath() {
        return getCcSwitchRootDir().resolve("cc-switch.db");
    }

    private String nullableText(@Nullable String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String safeMessage(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return e.getClass().getSimpleName();
        }
        return new String(message.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    private String uniqueImportedProviderName(String name) {
        String base = name == null || name.isBlank() ? "Imported Provider" : name.trim();
        List<String> existingNames = ProviderService.getInstance().getProviders().stream()
                .map(Provider::getName)
                .filter(Objects::nonNull)
                .toList();
        if (!existingNames.contains(base)) {
            return base;
        }
        int index = 2;
        while (existingNames.contains(base + " (" + index + ")")) {
            index++;
        }
        return base + " (" + index + ")";
    }
}
