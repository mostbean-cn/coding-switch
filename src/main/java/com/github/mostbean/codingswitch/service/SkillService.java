package com.github.mostbean.codingswitch.service;

import com.github.mostbean.codingswitch.model.CliType;
import com.github.mostbean.codingswitch.model.Skill;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystemException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributeView;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Skills 管理服务。
 * 管理 Claude Skills 的发现、安装和卸载。
 */
@Service(Service.Level.APP)
@State(name = "CodingSwitchSkills", storages = @Storage("coding-switch-skills.xml"))
public final class SkillService implements PersistentStateComponent<SkillService.State> {

    private static final Logger LOG = Logger.getInstance(SkillService.class);
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .enableComplexMapKeySerialization()
            .create();
    private static final long GIT_CHECK_CACHE_MS = 15_000L;
    private static final long REPO_CACHE_TTL_MS = 60 * 60 * 1000L;
    private static final long REPO_CACHE_RATE_LIMIT_TTL_MS = 60 * 1000L;
    private static final long REPO_CACHE_ERROR_TTL_MS = 5 * 60 * 1000L;
    private static final DateTimeFormatter RATE_LIMIT_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String SKILL_BRIDGE_START = "<!-- coding-switch:skills-bridge:start -->";
    private static final String SKILL_BRIDGE_END = "<!-- coding-switch:skills-bridge:end -->";
    private static final String SKILL_BRIDGE_MANIFEST = ".coding-switch-managed.json";
    private static final String SKILL_TEMP_ROOT_DIR = ".coding-switch-tmp";
    private static final List<CliType> SKILL_SYNC_TARGET_CLIS = List.of(CliType.CLAUDE, CliType.CODEX, CliType.GEMINI, CliType.OPENCODE);

    /** 内置推荐的 Skills 仓库列表 */
    public static final List<String> DEFAULT_REPOS = List.of(
            "https://github.com/anthropics/skills",
            "https://github.com/JimLiu/baoyu-skills");
    private static final List<String> REMOVED_DEFAULT_REPOS = List.of(
            "https://github.com/ComposioHQ/awesome-claude-skills");

    public static class State {
        public String skillsJson = "[]";
        public String customRepoConfigsJson = "[]";
        public String customReposJson = "[]";
    }

    public record RepoOption(String repositoryUrl, String branch, boolean builtIn) {
    }

    private static final class CustomRepoConfig {
        String repositoryUrl;
        String branch;

        CustomRepoConfig() {
        }

        CustomRepoConfig(String repositoryUrl, String branch) {
            this.repositoryUrl = repositoryUrl;
            this.branch = branch;
        }
    }

    public record DiscoveryResult(int discovered, int refreshed, int total) {
    }

    public record OperationResult(boolean success, String message) {
    }

    public record RepoDiscoveryInfo(
            String repositoryUrl,
            String owner,
            String repo,
            String branch,
            List<String> skillNames,
            String errorMessage) {

        public int skillCount() {
            return skillNames == null ? 0 : skillNames.size();
        }

        public String displayName() {
            return owner + "/" + repo;
        }

        public String skillsPageUrl() {
            String b = (branch == null || branch.isBlank()) ? "main" : branch;
            String base = repositoryUrl == null ? "" : repositoryUrl.replaceAll("/+$", "");
            return base + "/tree/" + b + "/skills";
        }
    }

    public record RepoInstallResult(boolean success, int installed, int skipped, int failed, String message) {
    }

    public record SkillBridgeSyncResult(int updated, int failed, String detail) {
    }

    private record RepoMeta(String owner, String repo, String htmlUrl, String defaultBranch) {
    }

    private record SkillsPathResult(List<String> skillNames, String errorMessage) {
    }

    private record RateLimitInfo(Integer remaining, Long resetEpochSecond, Integer retryAfterSeconds, String message) {
    }

    private record ZipAttemptResult(boolean success, boolean branchNotFound, String message) {
    }

    private State myState = new State();
    private final List<Runnable> changeListeners = new ArrayList<>();
    private volatile long gitLastCheckedAt = 0L;
    private volatile boolean gitAvailableCached = false;

    /** 仓库发现结果缓存，key=repoUrl，1 小时过期 */
    private record CachedRepo(RepoDiscoveryInfo info, long expireAt) {
    }

    private final Map<String, CachedRepo> repoCache = new HashMap<>();

    public static SkillService getInstance() {
        return ApplicationManager.getApplication().getService(SkillService.class);
    }

    @Override
    public @Nullable State getState() {
        return myState;
    }

    @Override
    public void loadState(@NotNull State state) {
        myState = normalizeState(state);
    }

    // =====================================================================
    // Skills CRUD
    // =====================================================================

    private volatile boolean didMigration = false;

    public List<Skill> getSkills() {
        if (!didMigration) {
            didMigration = true;
            migrateOldClaudeSkillsToGlobal();
        }
        try {
            State activeState = getActiveState();
            List<Skill> list = GSON.fromJson(activeState.skillsJson,
                    new TypeToken<List<Skill>>() {
                    }.getType());
            if (list == null) {
                return new ArrayList<>();
            }
            boolean normalized = false;
            for (Skill skill : list) {
                normalized = normalizeSkill(skill) || normalized;
            }
            boolean removedLegacyPlaceholders = list.removeIf(SkillService::isLegacyRepoPlaceholder);
            boolean removedTempEntries = list.removeIf(SkillService::isTemporaryInstallArtifact);
            if (normalized || removedLegacyPlaceholders || removedTempEntries) {
                saveSkills(list);
            }
            return list;
        } catch (Exception e) {
            LOG.warn("Failed to parse skills", e);
            return new ArrayList<>();
        }
    }

    private void migrateOldClaudeSkillsToGlobal() {
        try {
            Path globalDir = ConfigFileService.getInstance().getGlobalSkillsDir();
            Path claudeDir = ConfigFileService.getInstance().getClaudeSkillsDir();
            if (Files.isDirectory(claudeDir) && !Files.exists(globalDir)) {
                Files.createDirectories(globalDir);
                copyRecursively(claudeDir, globalDir);
                LOG.info("Migrated existing skills from " + claudeDir + " to " + globalDir);
            }
        } catch (Exception e) {
            LOG.warn("Failed to migrate old skills", e);
        }
    }

    public List<Skill> getInstalledSkills() {
        return getSkills().stream().filter(Skill::isInstalled).toList();
    }

    public boolean isGitAvailable() {
        long now = System.currentTimeMillis();
        if (now - gitLastCheckedAt <= GIT_CHECK_CACHE_MS) {
            return gitAvailableCached;
        }

        gitAvailableCached = detectGitAvailable();
        gitLastCheckedAt = now;
        return gitAvailableCached;
    }

    public List<RepoDiscoveryInfo> discoverSkillRepositories() {
        List<RepoOption> repoOptions = getAllRepoOptions();
        return repoOptions.parallelStream()
                .map(repoOption -> discoverSkillRepository(repoOption, false))
                .toList();
    }

    public RepoDiscoveryInfo discoverSkillRepository(String repoUrl) {
        return discoverSkillRepository(repoUrl, null, false);
    }

    public synchronized RepoDiscoveryInfo discoverSkillRepository(String repoUrl, boolean forceRefresh) {
        return discoverSkillRepository(repoUrl, null, forceRefresh);
    }

    public RepoDiscoveryInfo discoverSkillRepository(String repoUrl, @Nullable String branch) {
        return discoverSkillRepository(repoUrl, branch, false);
    }

    public synchronized RepoDiscoveryInfo discoverSkillRepository(String repoUrl, @Nullable String branch,
            boolean forceRefresh) {
        return discoverSkillRepository(new RepoOption(repoUrl, branch, false), forceRefresh);
    }

    public synchronized RepoDiscoveryInfo discoverSkillRepository(RepoOption repoOption, boolean forceRefresh) {
        if (repoOption == null || repoOption.repositoryUrl() == null || repoOption.repositoryUrl().isBlank()) {
            return new RepoDiscoveryInfo(
                    "",
                    "unknown",
                    "unknown",
                    "main",
                    List.of(),
                    "Invalid GitHub repository URL");
        }

        String repoUrl = repoOption.repositoryUrl().trim();
        String preferredBranch = normalizeBranch(repoOption.branch());
        String cacheKey = buildRepoCacheKey(repoUrl, preferredBranch);
        long now = System.currentTimeMillis();
        if (!forceRefresh) {
            CachedRepo cached = repoCache.get(cacheKey);
            if (cached != null && now < cached.expireAt()) {
                return cached.info();
            }
        }
        RepoDiscoveryInfo info = fetchRepoDiscovery(repoUrl, preferredBranch);
        repoCache.put(cacheKey, new CachedRepo(info, now + resolveRepoCacheTtlMs(info)));
        return info;
    }

    private String buildRepoCacheKey(String repoUrl, @Nullable String branch) {
        RepoRef parsed = parseRepo(repoUrl);
        String normalizedUrl = parsed == null ? repoUrl.trim() : parsed.normalizedUrl();
        String normalizedBranch = normalizeBranch(branch);
        return normalizedUrl.toLowerCase(Locale.ROOT) + "#"
                + (normalizedBranch == null ? "" : normalizedBranch.toLowerCase(Locale.ROOT));
    }

    private long resolveRepoCacheTtlMs(RepoDiscoveryInfo info) {
        if (info == null || info.errorMessage() == null || info.errorMessage().isBlank()) {
            return REPO_CACHE_TTL_MS;
        }
        return isRateLimitMessage(info.errorMessage()) ? REPO_CACHE_RATE_LIMIT_TTL_MS : REPO_CACHE_ERROR_TTL_MS;
    }

    private RepoDiscoveryInfo fetchRepoDiscovery(String repoUrl, @Nullable String preferredBranch) {
        RepoRef parsed = parseRepo(repoUrl);
        if (parsed == null) {
            return new RepoDiscoveryInfo(
                    repoUrl,
                    "unknown",
                    "unknown",
                    "main",
                    List.of(),
                    "Invalid GitHub repository URL");
        }

        RepoMeta meta = fetchRepoMeta(parsed);
        String owner = meta != null ? meta.owner() : parsed.owner();
        String repo = meta != null ? meta.repo() : parsed.repoName();
        String normalizedUrl = meta != null && meta.htmlUrl() != null && !meta.htmlUrl().isBlank()
                ? meta.htmlUrl()
                : parsed.normalizedUrl();
        String branch = normalizeBranch(preferredBranch);
        if (branch == null) {
            branch = meta != null ? normalizeBranch(meta.defaultBranch()) : null;
        }
        if (branch == null || branch.isBlank()) {
            branch = fetchDefaultBranch(parsed);
        }
        if (branch == null || branch.isBlank()) {
            branch = "main";
        }

        SkillsPathResult skills = fetchSkillNames(owner, repo, branch);
        return new RepoDiscoveryInfo(
                normalizedUrl,
                owner,
                repo,
                branch,
                skills.skillNames(),
                skills.errorMessage());
    }

    public RepoInstallResult installSkillsFromRepository(RepoDiscoveryInfo repoInfo) {
        return installSkillsFromRepository(repoInfo, null, false);
    }

    private RepoInstallResult installSkillsFromRepository(RepoDiscoveryInfo repoInfo, @Nullable Skill packageHint,
            boolean replaceOwnedChildren) {
        if (repoInfo == null) {
            return new RepoInstallResult(false, 0, 0, 0, "Repository not selected");
        }

        RepoRef repoRef = parseRepo(repoInfo.repositoryUrl());
        if (repoRef == null) {
            return new RepoInstallResult(false, 0, 0, 0, "Invalid GitHub repository URL");
        }

        RepoDiscoveryInfo resolved = repoInfo;
        if (resolved.skillNames() == null || resolved.skillNames().isEmpty()) {
            resolved = discoverSkillRepository(repoInfo.repositoryUrl(), repoInfo.branch(), false);
        }
        if (resolved.skillNames() == null || resolved.skillNames().isEmpty()) {
            return new RepoInstallResult(false, 0, 0, 0, "No skills found in repository");
        }
        Skill existingPackage = packageHint != null ? packageHint : findRepositoryPackage(resolved);

        Path tempRoot = null;
        try {
            Path skillsDir = ConfigFileService.getInstance().getGlobalSkillsDir();
            Files.createDirectories(skillsDir);
            tempRoot = createSkillTempDir(skillsDir, ".repo-install-");

            Path extractedRepoRoot = extractRepositoryZip(repoRef, resolved.branch(), tempRoot);
            if (extractedRepoRoot == null) {
                return new RepoInstallResult(false, 0, 0, 0, "Failed to download repository ZIP");
            }

            Path repoSkillsDir = extractedRepoRoot.resolve("skills");
            boolean hasSkillsDirectory = Files.isDirectory(repoSkillsDir);
            boolean isSingleSkillRepo = !hasSkillsDirectory && hasSkillManifestFile(extractedRepoRoot);
            if (!hasSkillsDirectory && !isSingleSkillRepo) {
                return new RepoInstallResult(false, 0, 0, 0,
                        "Repository does not contain skills directory or root SKILL.md");
            }

            int installed = 0;
            int skipped = 0;
            int failed = 0;
            List<String> failedNames = new ArrayList<>();
            List<Skill.SkillChild> children = new ArrayList<>();

            for (String skillName : resolved.skillNames()) {
                if (skillName == null || skillName.isBlank()) {
                    continue;
                }

                Path sourceDir = hasSkillsDirectory ? repoSkillsDir.resolve(skillName) : extractedRepoRoot;
                if (!Files.isDirectory(sourceDir)) {
                    failed++;
                    failedNames.add(skillName);
                    continue;
                }

                Path targetDir = skillsDir.resolve(safeFolderName(skillName));
                if (Files.exists(targetDir)) {
                    if (replaceOwnedChildren && isOwnedRepositoryChild(existingPackage, skillName, targetDir)) {
                        try {
                            deleteRecursively(targetDir);
                            copyRecursively(sourceDir, targetDir);
                            installed++;
                            children.add(createRepositoryChild(skillName, hasSkillsDirectory, targetDir, true, true));
                        } catch (Exception e) {
                            failed++;
                            failedNames.add(skillName);
                            cleanupPartialDirectory(targetDir);
                            children.add(createRepositoryChild(skillName, hasSkillsDirectory, targetDir,
                                    Files.isDirectory(targetDir), true));
                            LOG.warn("Failed to update repository skill child: " + skillName, e);
                        }
                    } else {
                        skipped++;
                        children.add(createRepositoryChild(skillName, hasSkillsDirectory, targetDir,
                                Files.isDirectory(targetDir), false));
                    }
                    continue;
                }

                try {
                    copyRecursively(sourceDir, targetDir);
                    installed++;
                    children.add(createRepositoryChild(skillName, hasSkillsDirectory, targetDir, true, true));
                } catch (Exception e) {
                    failed++;
                    failedNames.add(skillName);
                    cleanupPartialDirectory(targetDir);
                    children.add(createRepositoryChild(skillName, hasSkillsDirectory, targetDir,
                            Files.isDirectory(targetDir), true));
                    LOG.warn("Failed to install skill from repository: " + skillName, e);
                }
            }

            if (!children.isEmpty() || existingPackage != null) {
                upsertRepositoryPackage(resolved, mergeRepositoryChildren(existingPackage, children), skillsDir);
            }
            syncSkillBridgesToCli();

            StringBuilder message = new StringBuilder();
            message.append("Installed: ").append(installed)
                    .append(", Skipped: ").append(skipped)
                    .append(", Failed: ").append(failed);
            if (!failedNames.isEmpty()) {
                message.append(" (failed: ")
                        .append(String.join(", ", failedNames.stream().limit(5).toList()));
                if (failedNames.size() > 5) {
                    message.append("...");
                }
                message.append(")");
            }

            return new RepoInstallResult(failed == 0, installed, skipped, failed, message.toString());
        } catch (Exception e) {
            LOG.warn("Failed to install repository skills: " + repoInfo.repositoryUrl(), e);
            return new RepoInstallResult(false, 0, 0, 0, e.getMessage());
        } finally {
            if (tempRoot != null) {
                try {
                    deleteRecursively(tempRoot);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private void upsertRepositoryPackage(RepoDiscoveryInfo repoInfo, List<Skill.SkillChild> children, Path skillsDir) {
        List<Skill> skills = new ArrayList<>(getSkills());
        String branch = normalizeBranch(repoInfo.branch());
        String packageName = repoInfo.displayName();

        Skill target = null;
        for (Skill existing : skills) {
            if (existing == null || !existing.isRepositoryPackage()) {
                continue;
            }
            if (sameRepoAndBranch(existing.getRepository(), existing.getBranch(), repoInfo.repositoryUrl(), branch)) {
                target = existing;
                break;
            }
        }

        if (target == null) {
            target = new Skill();
            target.setName(packageName);
            skills.add(target);
        }

        target.setKind(Skill.Kind.REPOSITORY);
        target.setName(packageName);
        target.setRepository(repoInfo.repositoryUrl());
        target.setBranch(branch);
        target.setPath(repoInfo.skillsPageUrl());
        target.setDescription("GitHub: " + packageName + " (" + children.size() + " skills)");
        target.setInstalled(children.stream().anyMatch(Skill.SkillChild::isInstalled));
        target.setLocalPath(skillsDir.toString());
        target.setChildren(children);
        saveSkills(skills);
    }

    private Skill.SkillChild createRepositoryChild(String skillName, boolean hasSkillsDirectory, Path targetDir,
            boolean installed, boolean owned) {
        return new Skill.SkillChild(
                skillName,
                hasSkillsDirectory ? "skills/" + skillName : "",
                targetDir.toString(),
                installed,
                owned);
    }

    private void cleanupPartialDirectory(Path targetDir) {
        if (targetDir == null || !Files.exists(targetDir)) {
            return;
        }
        try {
            deleteRecursively(targetDir);
        } catch (IOException cleanupError) {
            LOG.warn("Failed to cleanup partial repository skill directory: " + targetDir, cleanupError);
        }
    }

    private @Nullable Skill findRepositoryPackage(RepoDiscoveryInfo repoInfo) {
        if (repoInfo == null) {
            return null;
        }
        String branch = normalizeBranch(repoInfo.branch());
        for (Skill existing : getSkills()) {
            if (existing == null || !existing.isRepositoryPackage()) {
                continue;
            }
            if (sameRepoAndBranch(existing.getRepository(), existing.getBranch(), repoInfo.repositoryUrl(), branch)) {
                return existing;
            }
        }
        return null;
    }

    private boolean isOwnedRepositoryChild(@Nullable Skill repositoryPackage, String skillName, Path targetDir) {
        if (repositoryPackage == null || repositoryPackage.getChildren() == null) {
            return false;
        }
        Path normalizedTarget = targetDir.toAbsolutePath().normalize();
        for (Skill.SkillChild child : repositoryPackage.getChildren()) {
            if (child == null || !child.isOwned()) {
                continue;
            }
            if (child.getName() != null && safeFolderName(child.getName()).equalsIgnoreCase(safeFolderName(skillName))) {
                return true;
            }
            if (child.getLocalPath() != null && !child.getLocalPath().isBlank()) {
                try {
                    Path childPath = Path.of(child.getLocalPath()).toAbsolutePath().normalize();
                    if (childPath.equals(normalizedTarget)) {
                        return true;
                    }
                } catch (InvalidPathException ignored) {
                }
            }
        }
        return false;
    }

    private List<Skill.SkillChild> mergeRepositoryChildren(@Nullable Skill existingPackage,
            List<Skill.SkillChild> changedChildren) {
        LinkedHashMap<String, Skill.SkillChild> merged = new LinkedHashMap<>();
        boolean keepExisting = existingPackage != null && existingPackage.isInstalled();
        if (keepExisting && existingPackage.getChildren() != null) {
            for (Skill.SkillChild child : existingPackage.getChildren()) {
                if (child != null && child.getName() != null && !child.getName().isBlank()) {
                    merged.put(safeFolderName(child.getName()).toLowerCase(Locale.ROOT), child);
                }
            }
        }
        if (changedChildren != null) {
            for (Skill.SkillChild child : changedChildren) {
                if (child != null && child.getName() != null && !child.getName().isBlank()) {
                    merged.put(safeFolderName(child.getName()).toLowerCase(Locale.ROOT), child);
                }
            }
        }
        return new ArrayList<>(merged.values());
    }

    public DiscoveryResult discoverFromRepos() {
        List<Skill> skills = new ArrayList<>(getSkills());
        int discovered = 0;
        int refreshed = 0;

        for (RepoOption repoOption : getAllRepoOptions()) {
            RepoRef ref = parseRepo(repoOption.repositoryUrl());
            if (ref == null) {
                continue;
            }
            Skill existing = findByRepoOrName(skills, ref.normalizedUrl(), ref.repoName());
            if (existing == null) {
                Skill skill = new Skill();
                skill.setName(ref.repoName());
                skill.setRepository(ref.normalizedUrl());
                skill.setDescription("GitHub: " + ref.owner() + "/" + ref.repoName());
                Path localDir = ConfigFileService.getInstance().getGlobalSkillsDir().resolve(ref.repoName());
                if (Files.isDirectory(localDir)) {
                    skill.setInstalled(true);
                    skill.setLocalPath(localDir.toString());
                }
                skills.add(skill);
                discovered++;
            } else {
                if (existing.getRepository() == null || existing.getRepository().isBlank()) {
                    existing.setRepository(ref.normalizedUrl());
                }
                if (existing.getDescription() == null || existing.getDescription().isBlank()) {
                    existing.setDescription("GitHub: " + ref.owner() + "/" + ref.repoName());
                }
                refreshed++;
            }
        }

        syncInstallState(skills);
        saveSkills(skills);
        return new DiscoveryResult(discovered, refreshed, skills.size());
    }

    public SkillBridgeSyncResult syncSkillBridgesToCli() {
        ConfigFileService configService = ConfigFileService.getInstance();
        List<Skill> allSkills = getSkills();
        int updated = 0;
        int failed = 0;
        List<String> errors = new ArrayList<>();

        for (CliType cliType : SKILL_SYNC_TARGET_CLIS) {
            List<Skill> selected = allSkills.stream()
                    .filter(skill -> skill.isInstalled() && skill.isSyncedTo(cliType))
                    .toList();
            boolean hasInstalledSelected = !selected.isEmpty();
            try {
                syncSkillBridgeForCli(configService, cliType, expandRepositoryPackages(selected));
                if (hasInstalledSelected) {
                    updated++;
                }
            } catch (Exception e) {
                failed++;
                errors.add(cliType.getDisplayName() + ": " + e.getMessage());
                LOG.warn("Failed to sync skills for " + cliType, e);
            }
        }

        String detail = errors.isEmpty() ? "" : String.join("; ", errors);
        return new SkillBridgeSyncResult(updated, failed, detail);
    }

    private List<Skill> expandRepositoryPackages(List<Skill> selectedSkills) {
        List<Skill> expanded = new ArrayList<>();
        if (selectedSkills == null) {
            return expanded;
        }
        for (Skill skill : selectedSkills) {
            if (skill == null) {
                continue;
            }
            if (!skill.isRepositoryPackage()) {
                expanded.add(skill);
                continue;
            }
            List<Skill.SkillChild> children = skill.getChildren();
            if (children == null) {
                continue;
            }
            for (Skill.SkillChild child : children) {
                if (child == null || !child.isOwned() || !child.isInstalled()
                        || child.getName() == null || child.getName().isBlank()) {
                    continue;
                }
                Skill childSkill = new Skill();
                childSkill.setName(child.getName());
                childSkill.setDescription(skill.getDescription());
                childSkill.setRepository(skill.getRepository());
                childSkill.setBranch(skill.getBranch());
                childSkill.setPath(child.getRelativePath());
                childSkill.setInstalled(true);
                childSkill.setLocalPath(child.getLocalPath());
                expanded.add(childSkill);
            }
        }
        return dedupeExpandedSkills(expanded);
    }

    private List<Skill> dedupeExpandedSkills(List<Skill> skills) {
        LinkedHashMap<String, Skill> unique = new LinkedHashMap<>();
        for (Skill skill : skills) {
            if (skill == null || skill.getName() == null || skill.getName().isBlank()) {
                continue;
            }
            String key = safeFolderName(skill.getName()).toLowerCase(Locale.ROOT);
            unique.putIfAbsent(key, skill);
        }
        return new ArrayList<>(unique.values());
    }

    public OperationResult installSkill(String skillId) {
        List<Skill> skills = new ArrayList<>(getSkills());
        Skill skill = skills.stream().filter(s -> Objects.equals(s.getId(), skillId)).findFirst().orElse(null);
        if (skill == null) {
            return new OperationResult(false, "Skill not found");
        }
        if (skill.getRepository() == null || skill.getRepository().isBlank()) {
            return new OperationResult(false, "Skill repository is empty");
        }

        try {
            Path skillsDir = ConfigFileService.getInstance().getGlobalSkillsDir();
            Files.createDirectories(skillsDir);

            String folderName = safeFolderName(skill.getName());
            Path targetDir = skillsDir.resolve(folderName);
            RepoRef repoRef = parseRepo(skill.getRepository());

            OperationResult result;
            if (Files.exists(targetDir.resolve(".git"))) {
                if (!isGitAvailable()) {
                    return new OperationResult(false, "Git command not found");
                }
                result = runGit(targetDir, "git", "pull", "--ff-only");
            } else if (Files.exists(targetDir) && isDirNotEmpty(targetDir)) {
                return new OperationResult(false, "Target directory already exists and is not empty: " + targetDir);
            } else {
                if (isGitAvailable()) {
                    result = runGit(skillsDir, "git", "clone", "--depth", "1", skill.getRepository(),
                            targetDir.toString());
                } else {
                    if (repoRef == null) {
                        return new OperationResult(false,
                                "Git is unavailable and ZIP fallback only supports github.com repositories");
                    }
                    result = installSkillFromGithubZip(repoRef, targetDir);
                }
            }

            if (!result.success()) {
                return result;
            }

            skill.setInstalled(true);
            skill.setLocalPath(targetDir.toString());
            saveSkills(skills);
            syncSkillBridgesToCli();
            return new OperationResult(true, "Installed");
        } catch (Exception e) {
            LOG.warn("Failed to install skill: " + skill.getName(), e);
            return new OperationResult(false, e.getMessage());
        }
    }

    public OperationResult installSkillViaZip(String skillId) {
        List<Skill> skills = new ArrayList<>(getSkills());
        Skill skill = skills.stream().filter(s -> Objects.equals(s.getId(), skillId)).findFirst().orElse(null);
        if (skill == null) {
            return new OperationResult(false, "Skill not found");
        }
        if (skill.getRepository() == null || skill.getRepository().isBlank()) {
            return new OperationResult(false, "Skill repository is empty");
        }

        RepoRef repoRef = parseRepo(skill.getRepository());
        if (repoRef == null) {
            return new OperationResult(false, "Only github.com repositories support ZIP fallback");
        }

        try {
            Path skillsDir = ConfigFileService.getInstance().getGlobalSkillsDir();
            Files.createDirectories(skillsDir);

            String folderName = safeFolderName(skill.getName());
            Path targetDir = skillsDir.resolve(folderName);
            if (Files.exists(targetDir) && isDirNotEmpty(targetDir)) {
                return new OperationResult(false, "Target directory already exists and is not empty: " + targetDir);
            }

            OperationResult result = installSkillFromGithubZip(repoRef, targetDir);
            if (!result.success()) {
                return result;
            }

            skill.setInstalled(true);
            skill.setLocalPath(targetDir.toString());
            saveSkills(skills);
            syncSkillBridgesToCli();
            return new OperationResult(true, "Installed via ZIP");
        } catch (Exception e) {
            LOG.warn("Failed to install skill via zip: " + skill.getName(), e);
            return new OperationResult(false, e.getMessage());
        }
    }

    public OperationResult updateInstalledSkillViaZip(String skillId) {
        List<Skill> skills = new ArrayList<>(getSkills());
        Skill skill = skills.stream().filter(s -> Objects.equals(s.getId(), skillId)).findFirst().orElse(null);
        if (skill == null) {
            return new OperationResult(false, "Skill not found");
        }
        if (!skill.isInstalled()) {
            return new OperationResult(false, "Skill is not installed");
        }
        if (skill.getRepository() == null || skill.getRepository().isBlank()) {
            return new OperationResult(false, "Skill repository is empty");
        }

        RepoRef repoRef = parseRepo(skill.getRepository());
        if (repoRef == null) {
            return new OperationResult(false, "Only github.com repositories support ZIP fallback");
        }

        try {
            Path skillsDir = ConfigFileService.getInstance().getGlobalSkillsDir();
            Files.createDirectories(skillsDir);

            Path targetDir = skill.getLocalPath() != null && !skill.getLocalPath().isBlank()
                    ? Path.of(skill.getLocalPath())
                    : skillsDir.resolve(safeFolderName(skill.getName()));

            Path normalizedSkillsDir = skillsDir.toAbsolutePath().normalize();
            Path normalizedTargetDir = targetDir.toAbsolutePath().normalize();
            if (!normalizedTargetDir.startsWith(normalizedSkillsDir)) {
                return new OperationResult(false, "Invalid local path for skill update");
            }

            OperationResult result = installSkillFromGithubZip(repoRef, normalizedTargetDir);
            if (!result.success()) {
                return result;
            }

            skill.setInstalled(true);
            skill.setLocalPath(normalizedTargetDir.toString());
            saveSkills(skills);
            syncSkillBridgesToCli();
            return new OperationResult(true, "Updated via ZIP");
        } catch (Exception e) {
            LOG.warn("Failed to update skill via zip: " + skill.getName(), e);
            return new OperationResult(false, e.getMessage());
        }
    }

    public OperationResult importSkillsFromLocalZip(Path zipFile) {
        if (zipFile == null || !Files.isRegularFile(zipFile)) {
            return new OperationResult(false, "ZIP file not found");
        }

        Path tempRoot = null;
        try {
            Path skillsDir = ConfigFileService.getInstance().getGlobalSkillsDir();
            Files.createDirectories(skillsDir);
            tempRoot = createSkillTempDir(skillsDir, ".skill-import-");

            try (InputStream in = Files.newInputStream(zipFile)) {
                extractZip(in, tempRoot);
            }

            Path root = detectZipRootDir(tempRoot);
            if (root == null) {
                root = tempRoot;
            }

            List<Path> candidates = collectImportSkillDirs(root);
            if (candidates.isEmpty()) {
                return new OperationResult(false, "No installable skill directory found in ZIP");
            }

            List<Skill> skills = new ArrayList<>(getSkills());
            int installed = 0;
            int skipped = 0;
            int failed = 0;
            List<String> failedNames = new ArrayList<>();

            String sourceName = zipFile.getFileName() != null ? zipFile.getFileName().toString() : "local.zip";
            for (Path candidate : candidates) {
                String skillName = resolveImportedSkillName(candidate, sourceName);
                Path targetDir = skillsDir.resolve(safeFolderName(skillName));
                try {
                    if (Files.exists(targetDir)) {
                        skipped++;
                    } else {
                        copyRecursively(candidate, targetDir);
                        installed++;
                    }
                    upsertImportedSkill(skills, skillName, targetDir, sourceName);
                } catch (Exception e) {
                    failed++;
                    failedNames.add(skillName);
                    LOG.warn("Failed to import skill from local zip: " + skillName, e);
                }
            }

            syncInstallState(skills);
            saveSkills(skills);

            StringBuilder message = new StringBuilder();
            message.append("success ").append(installed)
                    .append(" / skipped ").append(skipped)
                    .append(" / failed ").append(failed);
            if (!failedNames.isEmpty()) {
                message.append(" (failed: ")
                        .append(String.join(", ", failedNames.stream().limit(5).toList()));
                if (failedNames.size() > 5) {
                    message.append("...");
                }
                message.append(")");
            }
            return new OperationResult(failed == 0, message.toString());
        } catch (Exception e) {
            LOG.warn("Failed to import local zip skill: " + zipFile, e);
            return new OperationResult(false, e.getMessage());
        } finally {
            if (tempRoot != null) {
                try {
                    deleteRecursively(tempRoot);
                } catch (IOException ignored) {
                }
            }
        }
    }

    public OperationResult updateInstalledSkill(String skillId) {
        List<Skill> skills = new ArrayList<>(getSkills());
        Skill skill = skills.stream().filter(s -> Objects.equals(s.getId(), skillId)).findFirst().orElse(null);
        if (skill == null) {
            return new OperationResult(false, "Skill not found");
        }
        if (!skill.isInstalled() || skill.getLocalPath() == null) {
            return new OperationResult(false, "Skill is not installed");
        }
        if (skill.isRepositoryPackage()) {
            return updateRepositoryPackage(skill);
        }
        if (!isGitAvailable()) {
            return new OperationResult(false, "Git command not found");
        }

        try {
            Path dir = Path.of(skill.getLocalPath());
            if (!Files.exists(dir.resolve(".git"))) {
                return new OperationResult(false, "Local skill directory is not a git repository");
            }
            OperationResult result = runGit(dir, "git", "pull", "--ff-only");
            if (!result.success()) {
                return result;
            }
            saveSkills(skills);
            syncSkillBridgesToCli();
            return new OperationResult(true, "Updated");
        } catch (Exception e) {
            LOG.warn("Failed to update skill: " + skill.getName(), e);
            return new OperationResult(false, e.getMessage());
        }
    }

    private OperationResult updateRepositoryPackage(Skill skill) {
        if (skill.getRepository() == null || skill.getRepository().isBlank()) {
            return new OperationResult(false, "Skill repository is empty");
        }
        try {
            RepoDiscoveryInfo repoInfo = discoverSkillRepository(skill.getRepository(), skill.getBranch(), true);
            RepoInstallResult result = installSkillsFromRepository(repoInfo, skill, true);
            return new OperationResult(result.success(), result.message());
        } catch (Exception e) {
            LOG.warn("Failed to update repository skill package: " + skill.getName(), e);
            return new OperationResult(false, e.getMessage());
        }
    }

    /**
     * 扫描本地全局 Skills 目录（~/.config/coding-switch/skills）中已安装的 Skills。
     */
    public List<Skill> scanLocalSkills() {
        List<Skill> localSkills = new ArrayList<>();
        Path skillsDir = ConfigFileService.getInstance().getGlobalSkillsDir();

        if (!Files.isDirectory(skillsDir)) {
            return localSkills;
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(skillsDir)) {
            for (Path entry : stream) {
                if (!Files.isDirectory(entry) || isTemporaryInstallDirectory(entry)) {
                    continue;
                }
                Skill skill = new Skill();
                skill.setName(entry.getFileName().toString());
                skill.setInstalled(true);
                skill.setLocalPath(entry.toString());
                // 尝试读取 README 作为描述
                Path readme = entry.resolve("README.md");
                if (Files.exists(readme)) {
                    String content = Files.readString(readme);
                    skill.setDescription(content.length() > 200 ? content.substring(0, 200) + "..." : content);
                }
                localSkills.add(skill);
            }
        } catch (IOException e) {
            LOG.warn("Failed to scan skills directory", e);
        }

        return localSkills;
    }

    /**
     * 彻底移除指定 Skill（删除本地目录及记录，清空所有 CLI 同步状态）。
     */
    public void removeSkill(String skillId) {
        List<Skill> skills = new ArrayList<>(getSkills());
        for (Skill skill : skills) {
            if (skill.getId().equals(skillId) && skill.isRepositoryPackage()) {
                removeRepositoryPackageLocalDirs(skill);
                break;
            }
            if (skill.getId().equals(skillId) && skill.getLocalPath() != null) {
                Path path = Path.of(skill.getLocalPath());
                if (Files.exists(path)) {
                    try {
                        deleteRecursively(path);
                    } catch (IOException e) {
                        LOG.warn("Failed to delete local skill directory: " + path, e);
                    }
                }
                break;
            }
        }
        skills.removeIf(skill -> skill.getId().equals(skillId));
        saveSkills(skills);
        syncSkillBridgesToCli();
    }

    private void removeRepositoryPackageLocalDirs(Skill skill) {
        Path baseDir = ConfigFileService.getInstance().getGlobalSkillsDir().toAbsolutePath().normalize();
        List<Skill.SkillChild> children = skill.getChildren();
        if (children == null) {
            return;
        }
        for (Skill.SkillChild child : children) {
            if (child == null || !child.isOwned() || child.getLocalPath() == null || child.getLocalPath().isBlank()) {
                continue;
            }
            try {
                Path childDir = Path.of(child.getLocalPath()).toAbsolutePath().normalize();
                if (childDir.startsWith(baseDir) && Files.exists(childDir)) {
                    deleteRecursively(childDir);
                }
            } catch (Exception e) {
                LOG.warn("Failed to delete repository child skill directory: " + child.getLocalPath(), e);
            }
        }
    }

    private void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }

        IOException last = null;
        for (int i = 0; i < 3; i++) {
            try {
                deleteRecursivelyOnce(path);
                return;
            } catch (IOException e) {
                last = e;
                sleepSilently(120);
            }
        }

        String message = "删除失败，文件可能被占用: " + path;
        if (last != null && last.getMessage() != null && !last.getMessage().isBlank()) {
            message = message + "\n" + last.getMessage();
        }
        throw new IOException(message, last);
    }

    private static void deleteRecursivelyOnce(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                deleteOnePath(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                if (Files.exists(file)) {
                    deleteOnePath(file);
                    return FileVisitResult.CONTINUE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null && !(exc instanceof AccessDeniedException) && !(exc instanceof FileSystemException)) {
                    throw exc;
                }
                deleteOnePath(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteOnePath(Path path) throws IOException {
        clearReadOnly(path);
        try {
            Files.deleteIfExists(path);
        } catch (FileSystemException e) {
            clearReadOnly(path);
            Files.deleteIfExists(path);
        }
    }

    private static void clearReadOnly(Path path) {
        try {
            DosFileAttributeView view = Files.getFileAttributeView(path, DosFileAttributeView.class);
            if (view != null) {
                if (view.readAttributes().isReadOnly()) {
                    view.setReadOnly(false);
                }
            }
        } catch (Exception ignored) {
        }

        try {
            path.toFile().setWritable(true, false);
        } catch (Exception ignored) {
        }
    }

    private static void sleepSilently(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void updateSkill(Skill skill) {
        List<Skill> skills = new ArrayList<>(getSkills());
        boolean found = false;
        for (int i = 0; i < skills.size(); i++) {
            if (Objects.equals(skills.get(i).getId(), skill.getId())) {
                skills.set(i, skill);
                found = true;
                break;
            }
        }
        // 对于通过点击发现直接修改的对象
        if (!found) {
            for (int i = 0; i < skills.size(); i++) {
                if (Objects.equals(skills.get(i).getName(), skill.getName())) {
                    skills.set(i, skill);
                    found = true;
                    break;
                }
            }
        }
        if (!found) {
            skills.add(skill);
        }
        saveSkills(skills);
    }

    public boolean syncLocalSkills(List<Skill> localSkills) {
        List<Skill> saved = new ArrayList<>(getSkills());
        boolean changed = false;

        for (Skill local : localSkills) {
            if (isLocalSkillManagedByRepositoryPackage(local, saved)) {
                continue;
            }
            boolean found = false;
            for (Skill ext : saved) {
                if (ext.isRepositoryPackage()) {
                    continue;
                }
                if (ext.getName().equals(local.getName())) {
                    ext.setInstalled(true);
                    ext.setLocalPath(local.getLocalPath());
                    if (ext.getDescription() == null || ext.getDescription().isBlank()) {
                        ext.setDescription(local.getDescription());
                    }
                    found = true;
                    changed = true;
                    break;
                }
            }
            if (!found) {
                saved.add(local);
                changed = true;
            }
        }

        for (Skill ext : saved) {
            if (ext.isRepositoryPackage()) {
                changed = syncRepositoryPackageInstallState(ext) || changed;
                continue;
            }
            if (ext.isInstalled()) {
                boolean stillThere = localSkills.stream().anyMatch(l -> l.getName().equals(ext.getName()));
                if (!stillThere) {
                    ext.setInstalled(false);
                    ext.setLocalPath(null);
                    changed = true;
                }
            }
        }

        if (changed) {
            saveSkills(saved);
        }
        return changed;
    }

    private boolean isLocalSkillManagedByRepositoryPackage(Skill local, List<Skill> saved) {
        if (local == null) {
            return false;
        }
        String localName = local.getName();
        String localPath = local.getLocalPath();
        Path normalizedLocalPath = null;
        if (localPath != null && !localPath.isBlank()) {
            try {
                normalizedLocalPath = Path.of(localPath).toAbsolutePath().normalize();
            } catch (InvalidPathException ignored) {
            }
        }

        for (Skill skill : saved) {
            if (skill == null || !skill.isRepositoryPackage() || skill.getChildren() == null) {
                continue;
            }
            for (Skill.SkillChild child : skill.getChildren()) {
                if (child == null || !child.isOwned()) {
                    continue;
                }
                if (localName != null && child.getName() != null
                        && safeFolderName(child.getName()).equalsIgnoreCase(safeFolderName(localName))) {
                    return true;
                }
                if (normalizedLocalPath != null && child.getLocalPath() != null && !child.getLocalPath().isBlank()) {
                    try {
                        Path childPath = Path.of(child.getLocalPath()).toAbsolutePath().normalize();
                        if (normalizedLocalPath.equals(childPath)) {
                            return true;
                        }
                    } catch (InvalidPathException ignored) {
                    }
                }
            }
        }
        return false;
    }

    // =====================================================================
    // 自定义仓库管理
    // =====================================================================

    public List<String> getCustomRepos() {
        return getCustomRepoOptions().stream().map(RepoOption::repositoryUrl).toList();
    }

    public List<RepoOption> getCustomRepoOptions() {
        State activeState = getActiveState();
        List<CustomRepoConfig> fromConfig = parseCustomRepoConfigs();
        List<String> legacyRepos = parseLegacyCustomRepos();

        LinkedHashMap<String, RepoOption> merged = new LinkedHashMap<>();
        boolean changed = false;

        for (CustomRepoConfig config : fromConfig) {
            RepoOption sanitized = sanitizeCustomRepoOption(config.repositoryUrl, config.branch);
            if (sanitized == null) {
                changed = true;
                continue;
            }
            if (!sameRepoAndBranch(config.repositoryUrl, config.branch, sanitized.repositoryUrl(), sanitized.branch())) {
                changed = true;
            }
            String key = sanitized.repositoryUrl().toLowerCase(Locale.ROOT);
            RepoOption existing = merged.get(key);
            if (existing == null) {
                merged.put(key, sanitized);
                continue;
            }
            String mergedBranch = normalizeBranch(existing.branch());
            String candidateBranch = normalizeBranch(sanitized.branch());
            if ((mergedBranch == null || mergedBranch.isBlank()) && candidateBranch != null) {
                merged.put(key, new RepoOption(existing.repositoryUrl(), candidateBranch, false));
            }
            changed = true;
        }

        for (String legacyRepoUrl : legacyRepos) {
            RepoOption sanitized = sanitizeCustomRepoOption(legacyRepoUrl, null);
            if (sanitized == null) {
                changed = true;
                continue;
            }
            String key = sanitized.repositoryUrl().toLowerCase(Locale.ROOT);
            if (!merged.containsKey(key)) {
                merged.put(key, sanitized);
            } else {
                changed = true;
            }
        }

        List<RepoOption> result = new ArrayList<>(merged.values());
        String normalizedConfigJson = GSON.toJson(toCustomRepoConfigs(result));
        boolean legacyNotEmpty = activeState.customReposJson != null && !activeState.customReposJson.isBlank()
                && !"[]".equals(activeState.customReposJson);
        if (changed || !normalizedConfigJson.equals(activeState.customRepoConfigsJson) || legacyNotEmpty) {
            activeState.customRepoConfigsJson = normalizedConfigJson;
            activeState.customReposJson = "[]";
            saveActiveState(activeState);
        }

        return result;
    }

    public void addCustomRepo(String repoUrl) {
        addOrUpdateCustomRepo(repoUrl, null);
    }

    public void addOrUpdateCustomRepo(String repoUrl, @Nullable String branch) {
        RepoOption sanitized = sanitizeCustomRepoOption(repoUrl, branch);
        if (sanitized == null) {
            return;
        }

        List<RepoOption> repos = new ArrayList<>(getCustomRepoOptions());
        for (int i = 0; i < repos.size(); i++) {
            RepoOption existing = repos.get(i);
            if (!sameRepo(existing.repositoryUrl(), sanitized.repositoryUrl())) {
                continue;
            }
            if (Objects.equals(normalizeBranch(existing.branch()), normalizeBranch(sanitized.branch()))) {
                return;
            }
            repos.set(i, sanitized);
            persistCustomRepoOptions(repos);
            fireChanged();
            return;
        }

        repos.add(sanitized);
        persistCustomRepoOptions(repos);
        fireChanged();
    }

    public void removeCustomRepo(String repoUrl) {
        removeCustomRepo(repoUrl, null);
    }

    public void removeCustomRepo(String repoUrl, @Nullable String branch) {
        RepoRef target = parseRepo(repoUrl);
        if (target == null) {
            return;
        }
        String normalizedBranch = normalizeBranch(branch);
        List<RepoOption> repos = new ArrayList<>(getCustomRepoOptions());
        boolean removed = repos.removeIf(existing -> sameRepo(existing.repositoryUrl(), target.normalizedUrl())
                && (normalizedBranch == null
                        || Objects.equals(normalizeBranch(existing.branch()), normalizedBranch)));
        if (!removed) {
            return;
        }
        persistCustomRepoOptions(repos);
        fireChanged();
    }

    public List<RepoOption> getAllRepoOptions() {
        List<RepoOption> all = new ArrayList<>();
        for (String repoUrl : DEFAULT_REPOS) {
            RepoOption builtIn = createBuiltInRepoOption(repoUrl);
            if (builtIn == null) {
                continue;
            }
            all.add(builtIn);
        }
        all.addAll(getCustomRepoOptions());
        return all;
    }

    public List<String> getAllRepos() {
        return getAllRepoOptions().stream().map(RepoOption::repositoryUrl).toList();
    }

    private void persistCustomRepoOptions(List<RepoOption> repoOptions) {
        State activeState = getActiveState();
        activeState.customRepoConfigsJson = GSON.toJson(toCustomRepoConfigs(repoOptions));
        activeState.customReposJson = "[]";
        saveActiveState(activeState);
    }

    private List<String> parseLegacyCustomRepos() {
        try {
            List<String> list = GSON.fromJson(getActiveState().customReposJson,
                    new TypeToken<List<String>>() {
                    }.getType());
            return list == null ? new ArrayList<>() : list;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private List<CustomRepoConfig> parseCustomRepoConfigs() {
        try {
            List<CustomRepoConfig> list = GSON.fromJson(getActiveState().customRepoConfigsJson,
                    new TypeToken<List<CustomRepoConfig>>() {
                    }.getType());
            return list == null ? new ArrayList<>() : list;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private static List<CustomRepoConfig> toCustomRepoConfigs(List<RepoOption> repoOptions) {
        List<CustomRepoConfig> configs = new ArrayList<>();
        for (RepoOption repoOption : repoOptions) {
            if (repoOption == null || repoOption.repositoryUrl() == null || repoOption.repositoryUrl().isBlank()) {
                continue;
            }
            configs.add(new CustomRepoConfig(repoOption.repositoryUrl(), normalizeBranch(repoOption.branch())));
        }
        return configs;
    }

    private @Nullable RepoOption sanitizeCustomRepoOption(String repoUrl, @Nullable String branch) {
        RepoRef ref = parseRepo(repoUrl);
        if (ref == null) {
            return null;
        }
        String normalized = ref.normalizedUrl();
        if (isBuiltInRepo(normalized)) {
            return null;
        }
        return new RepoOption(normalized, normalizeBranch(branch), false);
    }

    private @Nullable RepoOption createBuiltInRepoOption(String repoUrl) {
        RepoRef ref = parseRepo(repoUrl);
        if (ref == null) {
            return null;
        }
        return new RepoOption(ref.normalizedUrl(), null, true);
    }

    private record RepoRef(String owner, String repoName, String normalizedUrl) {
    }

    private RepoRef parseRepo(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return null;
        }
        String url = rawUrl.trim();
        if (url.endsWith(".git")) {
            url = url.substring(0, url.length() - 4);
        }
        String marker = "github.com/";
        int idx = url.toLowerCase(Locale.ROOT).indexOf(marker);
        String suffix;
        if (idx >= 0) {
            suffix = url.substring(idx + marker.length());
        } else if (url.matches("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+(?:/.*)?$")) {
            suffix = url;
        } else {
            return null;
        }
        String[] parts = suffix.split("/");
        if (parts.length < 2) {
            return null;
        }
        String owner = parts[0];
        String repo = parts[1];
        if (owner.isBlank() || repo.isBlank()) {
            return null;
        }
        String normalized = "https://github.com/" + owner + "/" + repo;
        return new RepoRef(owner, repo, normalized);
    }

    private static @Nullable String normalizeBranch(@Nullable String rawBranch) {
        if (rawBranch == null) {
            return null;
        }
        String branch = rawBranch.trim();
        if (branch.isBlank()) {
            return null;
        }
        String prefix = "refs/heads/";
        if (branch.startsWith(prefix) && branch.length() > prefix.length()) {
            branch = branch.substring(prefix.length());
        }
        while (branch.endsWith("/")) {
            branch = branch.substring(0, branch.length() - 1);
        }
        return branch.isBlank() ? null : branch;
    }

    private boolean isBuiltInRepo(String repoUrl) {
        return containsRepo(DEFAULT_REPOS, repoUrl) || containsRepo(REMOVED_DEFAULT_REPOS, repoUrl);
    }

    private boolean sameRepoAndBranch(String leftUrl, @Nullable String leftBranch, String rightUrl,
            @Nullable String rightBranch) {
        return sameRepo(leftUrl, rightUrl)
                && Objects.equals(normalizeBranch(leftBranch), normalizeBranch(rightBranch));
    }

    private boolean containsRepo(List<String> repoList, String repoUrl) {
        for (String item : repoList) {
            if (sameRepo(item, repoUrl)) {
                return true;
            }
        }
        return false;
    }

    private boolean sameRepo(String left, String right) {
        RepoRef l = parseRepo(left);
        RepoRef r = parseRepo(right);
        if (l == null || r == null) {
            if (left == null || right == null) {
                return false;
            }
            return left.trim().equalsIgnoreCase(right.trim());
        }
        return l.normalizedUrl().equalsIgnoreCase(r.normalizedUrl());
    }

    private RepoMeta fetchRepoMeta(RepoRef repoRef) {
        HttpURLConnection conn = null;
        try {
            String apiUrl = "https://api.github.com/repos/" + repoRef.owner() + "/" + repoRef.repoName();
            conn = openGetConnection(apiUrl);
            int code = conn.getResponseCode();
            if (isRedirectCode(code)) {
                String location = conn.getHeaderField("Location");
                if (location != null && !location.isBlank()) {
                    conn.disconnect();
                    conn = openGetConnection(location);
                    code = conn.getResponseCode();
                }
            }
            if (code < 200 || code >= 300) {
                return null;
            }

            try (InputStream in = conn.getInputStream();
                    InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                String fullName = getJsonString(root, "full_name");
                String htmlUrl = getJsonString(root, "html_url");
                String defaultBranch = getJsonString(root, "default_branch");

                String owner = repoRef.owner();
                String repo = repoRef.repoName();
                if (fullName != null && fullName.contains("/")) {
                    String[] parts = fullName.split("/");
                    if (parts.length >= 2) {
                        owner = parts[0];
                        repo = parts[1];
                    }
                }
                if (htmlUrl == null || htmlUrl.isBlank()) {
                    htmlUrl = "https://github.com/" + owner + "/" + repo;
                }
                return new RepoMeta(owner, repo, htmlUrl, defaultBranch);
            }
        } catch (Exception e) {
            LOG.debug("Failed to fetch repo metadata: " + e.getMessage());
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private SkillsPathResult fetchSkillNames(String owner, String repo, String branch) {
        List<String> branchCandidates = new ArrayList<>();
        addBranchCandidate(branchCandidates, branch);
        addBranchCandidate(branchCandidates, "main");
        addBranchCandidate(branchCandidates, "master");

        String lastError = null;
        for (String currentBranch : branchCandidates) {
            HttpURLConnection conn = null;
            try {
                String apiUrl = "https://api.github.com/repos/" + owner + "/" + repo
                        + "/contents/skills?ref=" + urlEncode(currentBranch);
                conn = openGetConnection(apiUrl);
                int code = conn.getResponseCode();
                if (code == 404) {
                    continue;
                }
                if (code < 200 || code >= 300) {
                    String detail = readErrorPreview(conn);
                    if (isRateLimitResponse(code, conn, detail)) {
                        List<String> fallbackNames = fetchSkillNamesFromZip(owner, repo, currentBranch);
                        if (!fallbackNames.isEmpty()) {
                            return new SkillsPathResult(fallbackNames, null);
                        }
                        return new SkillsPathResult(List.of(), buildRateLimitMessage(conn, detail));
                    }
                    lastError = detail == null || detail.isBlank() ? "HTTP " + code : "HTTP " + code + " - " + detail;
                    continue;
                }

                try (InputStream in = conn.getInputStream();
                        InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                    var element = JsonParser.parseReader(reader);
                    if (!element.isJsonArray()) {
                        return new SkillsPathResult(List.of(), null);
                    }
                    List<String> names = new ArrayList<>();
                    element.getAsJsonArray().forEach(item -> {
                        if (!item.isJsonObject()) {
                            return;
                        }
                        JsonObject obj = item.getAsJsonObject();
                        String type = getJsonString(obj, "type");
                        String name = getJsonString(obj, "name");
                        if ("dir".equals(type) && name != null && !name.isBlank()) {
                            names.add(name);
                        }
                    });
                    return new SkillsPathResult(names, null);
                }
            } catch (Exception e) {
                lastError = e.getMessage();
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }

        List<String> fallbackNames = fetchSkillNamesFromZip(owner, repo, branch);
        if (!fallbackNames.isEmpty()) {
            return new SkillsPathResult(fallbackNames, null);
        }
        return new SkillsPathResult(List.of(), lastError);
    }

    private List<String> fetchSkillNamesFromZip(String owner, String repo, String branch) {
        RepoRef repoRef = new RepoRef(owner, repo, "https://github.com/" + owner + "/" + repo);
        Path tempRoot = null;
        try {
            tempRoot = Files.createTempDirectory("coding-switch-discovery-");
            Path extractedRepoRoot = extractRepositoryZip(repoRef, branch, tempRoot);
            if (extractedRepoRoot == null) {
                return List.of();
            }
            Path skillsDir = extractedRepoRoot.resolve("skills");
            if (Files.isDirectory(skillsDir)) {
                List<String> names = new ArrayList<>();
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(skillsDir)) {
                    for (Path child : stream) {
                        if (Files.isDirectory(child)) {
                            String name = child.getFileName().toString();
                            if (name != null && !name.isBlank()) {
                                names.add(name.trim());
                            }
                        }
                    }
                }
                names.sort(String::compareToIgnoreCase);
                return names;
            }

            if (hasSkillManifestFile(extractedRepoRoot)) {
                return List.of(repo);
            }
            return List.of();
        } catch (Exception e) {
            LOG.debug("Failed to fallback discovery via repository ZIP: " + owner + "/" + repo, e);
            return List.of();
        } finally {
            if (tempRoot != null) {
                try {
                    deleteRecursively(tempRoot);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static boolean hasSkillManifestFile(Path directory) {
        if (directory == null) {
            return false;
        }
        return Files.isRegularFile(directory.resolve("SKILL.md"))
                || Files.isRegularFile(directory.resolve("skill.md"));
    }

    private Path extractRepositoryZip(RepoRef repoRef, String branch, Path tempRoot) throws IOException {
        Files.createDirectories(tempRoot);
        List<String> branchCandidates = new ArrayList<>();
        addBranchCandidate(branchCandidates, branch);
        addBranchCandidate(branchCandidates, "main");
        addBranchCandidate(branchCandidates, "master");

        for (String b : branchCandidates) {
            Path extractDir = tempRoot.resolve(b);
            Files.createDirectories(extractDir);
            ZipAttemptResult result = downloadAndExtractZip(repoRef, b, extractDir);
            if (!result.success()) {
                deleteRecursively(extractDir);
                continue;
            }

            Path root = detectZipRootDir(extractDir);
            if (root != null && Files.isDirectory(root)) {
                return root;
            }
            deleteRecursively(extractDir);
        }
        return null;
    }

    private static void copyRecursively(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path relative = source.relativize(dir);
                Path targetDir = target.resolve(relative);
                Files.createDirectories(targetDir);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path relative = source.relativize(file);
                Path targetFile = target.resolve(relative);
                Path parent = targetFile.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.copy(file, targetFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static String getJsonString(JsonObject obj, String key) {
        if (obj == null || key == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return null;
        }
        String value = obj.get(key).getAsString();
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private void syncSkillBridgeForCli(ConfigFileService configService, CliType cliType, List<Skill> selectedSkills)
            throws IOException {
        switch (cliType) {
            case CLAUDE -> syncClaudeCodeSkillsDirectory(configService, selectedSkills);
            case CODEX -> syncCodexSkillsDirectory(configService, selectedSkills);
            case GEMINI -> syncGeminiSkillsDirectory(configService, selectedSkills);
            case OPENCODE -> syncOpenCodeSkillBridge(configService, selectedSkills);
        }
    }

    private void syncClaudeCodeSkillsDirectory(ConfigFileService configService, List<Skill> selectedSkills)
            throws IOException {
        syncManagedSkillsDirectory(configService, configService.getClaudeSkillsDir(), selectedSkills);
    }

    private void syncCodexSkillsDirectory(ConfigFileService configService, List<Skill> selectedSkills)
            throws IOException {
        syncManagedSkillsDirectory(configService, configService.getCodexSkillsDir(), selectedSkills);
    }

    private void syncGeminiSkillsDirectory(ConfigFileService configService, List<Skill> selectedSkills)
            throws IOException {
        syncManagedSkillsDirectory(configService, configService.getGeminiSkillsDir(), selectedSkills);
    }

    private void syncManagedSkillsDirectory(ConfigFileService configService, Path targetSkillsDir,
            List<Skill> selectedSkills) throws IOException {
        Files.createDirectories(targetSkillsDir);

        List<String> expectedFolderNames = new ArrayList<>();
        if (selectedSkills != null) {
            for (Skill skill : selectedSkills) {
                if (skill != null && skill.getName() != null && !skill.getName().isBlank()) {
                    String folderName = safeFolderName(skill.getName());
                    if (!expectedFolderNames.contains(folderName)) {
                        expectedFolderNames.add(folderName);
                    }
                }
            }
        }

        Path normalizedTargetSkillsDir = targetSkillsDir.toAbsolutePath().normalize();
        Path globalSkillsDir = configService.getGlobalSkillsDir().toAbsolutePath().normalize();
        Path manifestPath = normalizedTargetSkillsDir.resolve(SKILL_BRIDGE_MANIFEST);
        List<String> previouslyManaged = readManagedFolders(manifestPath);

        for (String folderName : previouslyManaged) {
            if (expectedFolderNames.contains(folderName)) {
                continue;
            }
            Path targetDir = normalizedTargetSkillsDir.resolve(folderName).normalize();
            if (!targetDir.startsWith(normalizedTargetSkillsDir)) {
                continue;
            }
            if (Files.isDirectory(targetDir)) {
                deleteRecursively(targetDir);
            }
        }

        for (String folderName : expectedFolderNames) {
            Path sourceDir = globalSkillsDir.resolve(folderName).normalize();
            Path targetDir = normalizedTargetSkillsDir.resolve(folderName).normalize();
            if (!sourceDir.startsWith(globalSkillsDir) || !targetDir.startsWith(normalizedTargetSkillsDir)) {
                continue;
            }
            if (Files.isDirectory(sourceDir)) {
                if (Files.exists(targetDir)) {
                    deleteRecursively(targetDir);
                }
                Files.createDirectories(targetDir);
                copyRecursively(sourceDir, targetDir);
            }
        }
        writeManagedFolders(manifestPath, expectedFolderNames);
    }

    private void syncOpenCodeSkillBridge(ConfigFileService configService, List<Skill> selectedSkills)
            throws IOException {
        Path promptPath = resolveOpenCodeBridgePromptPath(configService);
        String existing = configService.readFile(promptPath);

        String merged;
        if (selectedSkills == null || selectedSkills.isEmpty()) {
            merged = removeManagedBlock(existing, SKILL_BRIDGE_START, SKILL_BRIDGE_END);
        } else {
            String block = buildSkillBridgeBlock(selectedSkills);
            merged = upsertManagedBlock(existing, block, SKILL_BRIDGE_START, SKILL_BRIDGE_END);
        }

        if (Objects.equals(existing, merged)) {
            return;
        }
        configService.writeFile(promptPath, merged);
    }

    private static Path resolveOpenCodeBridgePromptPath(ConfigFileService configService) {
        return configService.getPromptFilePath(CliType.OPENCODE).resolve("default.md");
    }

    private static String buildSkillBridgeBlock(List<Skill> selectedSkills) {
        List<Skill> sorted = selectedSkills.stream()
                .filter(skill -> skill != null && skill.getName() != null && !skill.getName().isBlank())
                .sorted(Comparator.comparing(skill -> skill.getName().toLowerCase(Locale.ROOT)))
                .toList();

        StringBuilder sb = new StringBuilder();
        sb.append(SKILL_BRIDGE_START).append("\n");
        sb.append("## Coding Switch Skills Bridge\n");
        sb.append("When a task matches a skill below, read its SKILL.md and follow it.\n\n");

        for (Skill skill : sorted) {
            String skillName = skill.getName();
            Path skillMd = resolveSkillMarkdownPath(skill);
            sb.append("- ").append(skillName).append("\n");
            if (skillMd != null) {
                sb.append("  - skill_md: `").append(skillMd).append("`\n");
            }
            if (skill.getRepository() != null && !skill.getRepository().isBlank()) {
                sb.append("  - repo: ").append(skill.getRepository()).append("\n");
            }
        }

        sb.append("\n").append(SKILL_BRIDGE_END).append("\n");
        return sb.toString();
    }

    @Nullable
    private static Path resolveSkillMarkdownPath(Skill skill) {
        if (skill == null) {
            return null;
        }
        try {
            String localPath = skill.getLocalPath();
            if (localPath == null || localPath.isBlank()) {
                return null;
            }
            Path base = Path.of(localPath);
            Path skillMd = base.resolve("SKILL.md");
            if (Files.exists(skillMd)) {
                return skillMd;
            }
            return base;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String upsertManagedBlock(String existing, String block, String startMarker, String endMarker) {
        String safeExisting = existing == null ? "" : existing;
        int start = safeExisting.indexOf(startMarker);
        int end = safeExisting.indexOf(endMarker);
        if (start >= 0 && end >= start) {
            int endExclusive = end + endMarker.length();
            if (endExclusive < safeExisting.length() && safeExisting.charAt(endExclusive) == '\n') {
                endExclusive++;
            }
            return safeExisting.substring(0, start) + block + safeExisting.substring(endExclusive);
        }
        if (safeExisting.isBlank()) {
            return block;
        }
        return safeExisting + (safeExisting.endsWith("\n") ? "\n" : "\n\n") + block;
    }

    private List<String> readManagedFolders(Path manifestPath) {
        try {
            if (!Files.exists(manifestPath)) {
                return new ArrayList<>();
            }
            String raw = ConfigFileService.getInstance().readFile(manifestPath);
            if (raw == null || raw.isBlank()) {
                return new ArrayList<>();
            }
            List<String> parsed = GSON.fromJson(raw, new TypeToken<List<String>>() {
            }.getType());
            if (parsed == null) {
                return new ArrayList<>();
            }
            List<String> result = new ArrayList<>();
            for (String folder : parsed) {
                if (folder != null && !folder.isBlank()) {
                    String sanitized = safeFolderName(folder);
                    if (!result.contains(sanitized)) {
                        result.add(sanitized);
                    }
                }
            }
            return result;
        } catch (Exception e) {
            LOG.warn("Failed to read skill bridge manifest: " + manifestPath, e);
            return new ArrayList<>();
        }
    }

    private void writeManagedFolders(Path manifestPath, List<String> folders) {
        try {
            List<String> normalized = new ArrayList<>();
            if (folders != null) {
                for (String folder : folders) {
                    if (folder == null || folder.isBlank()) {
                        continue;
                    }
                    String sanitized = safeFolderName(folder);
                    if (!normalized.contains(sanitized)) {
                        normalized.add(sanitized);
                    }
                }
            }
            ConfigFileService.getInstance().writeFile(manifestPath, GSON.toJson(normalized));
        } catch (Exception e) {
            LOG.warn("Failed to write skill bridge manifest: " + manifestPath, e);
        }
    }

    private static String removeManagedBlock(String existing, String startMarker, String endMarker) {
        String safeExisting = existing == null ? "" : existing;
        int start = safeExisting.indexOf(startMarker);
        int end = safeExisting.indexOf(endMarker);
        if (start < 0 || end < start) {
            return safeExisting;
        }
        int endExclusive = end + endMarker.length();
        if (endExclusive < safeExisting.length() && safeExisting.charAt(endExclusive) == '\n') {
            endExclusive++;
        }
        return safeExisting.substring(0, start) + safeExisting.substring(endExclusive);
    }

    private static boolean isRedirectCode(int code) {
        return code == 301 || code == 302 || code == 307 || code == 308;
    }

    private Skill findByRepoOrName(List<Skill> skills, String repoUrl, String repoName) {
        for (Skill skill : skills) {
            if (repoUrl.equalsIgnoreCase(String.valueOf(skill.getRepository()))) {
                return skill;
            }
            if (repoName.equalsIgnoreCase(String.valueOf(skill.getName()))) {
                return skill;
            }
        }
        return null;
    }

    private void syncInstallState(List<Skill> skills) {
        Path baseDir = ConfigFileService.getInstance().getGlobalSkillsDir();
        for (Skill skill : skills) {
            if (skill.isRepositoryPackage()) {
                syncRepositoryPackageInstallState(skill);
                continue;
            }
            String folderName = safeFolderName(skill.getName());
            Path localDir = baseDir.resolve(folderName);
            if (Files.isDirectory(localDir)) {
                skill.setInstalled(true);
                skill.setLocalPath(localDir.toString());
            } else {
                skill.setInstalled(false);
                skill.setLocalPath(null);
            }
        }
    }

    private boolean syncRepositoryPackageInstallState(Skill skill) {
        if (skill == null || !skill.isRepositoryPackage()) {
            return false;
        }
        boolean changed = false;
        boolean anyInstalled = false;
        Path baseDir = ConfigFileService.getInstance().getGlobalSkillsDir();
        List<Skill.SkillChild> children = skill.getChildren();
        if (children == null) {
            children = new ArrayList<>();
            skill.setChildren(children);
            changed = true;
        }

        for (Skill.SkillChild child : children) {
            if (child == null || child.getName() == null || child.getName().isBlank()) {
                continue;
            }
            Path localDir;
            try {
                localDir = child.getLocalPath() == null || child.getLocalPath().isBlank()
                        ? baseDir.resolve(safeFolderName(child.getName()))
                        : Path.of(child.getLocalPath());
            } catch (InvalidPathException e) {
                localDir = baseDir.resolve(safeFolderName(child.getName()));
            }
            boolean installed = Files.isDirectory(localDir);
            anyInstalled = anyInstalled || installed;
            if (child.isInstalled() != installed) {
                child.setInstalled(installed);
                changed = true;
            }
            if (installed && !Objects.equals(child.getLocalPath(), localDir.toString())) {
                child.setLocalPath(localDir.toString());
                changed = true;
            }
        }

        if (skill.isInstalled() != anyInstalled) {
            skill.setInstalled(anyInstalled);
            changed = true;
        }
        if (!Objects.equals(skill.getLocalPath(), baseDir.toString())) {
            skill.setLocalPath(baseDir.toString());
            changed = true;
        }
        return changed;
    }

    private static String safeFolderName(String name) {
        if (name == null || name.isBlank()) {
            return "skill";
        }
        String sanitized = name.replaceAll("[^a-zA-Z0-9._-]", "-");
        if (sanitized.isBlank() || ".".equals(sanitized) || "..".equals(sanitized)
                || SKILL_BRIDGE_MANIFEST.equals(sanitized) || SKILL_TEMP_ROOT_DIR.equals(sanitized)) {
            return "skill";
        }
        return sanitized;
    }

    private static boolean normalizeSkill(Skill skill) {
        if (skill == null) {
            return false;
        }

        boolean changed = false;
        if (skill.getId() == null || skill.getId().isBlank()) {
            skill.setId(UUID.randomUUID().toString());
            changed = true;
        }
        if (skill.getKind() == null) {
            skill.setKind(Skill.Kind.SINGLE);
            changed = true;
        }
        if (skill.getChildren() == null) {
            skill.setChildren(new ArrayList<>());
            changed = true;
        }
        if (skill.isRepositoryPackage()) {
            for (Skill.SkillChild child : skill.getChildren()) {
                if (child != null && child.getOwned() == null) {
                    child.setOwned(child.isInstalled() && child.getLocalPath() != null && !child.getLocalPath().isBlank());
                    changed = true;
                }
            }
        }

        Map<CliType, Boolean> syncTargets = skill.getSyncTargets();
        if (syncTargets == null) {
            syncTargets = new HashMap<>();
            skill.setSyncTargets(syncTargets);
            changed = true;
        }

        changed = putDefaultIfMissing(syncTargets, CliType.CLAUDE, false) || changed;
        changed = putDefaultIfMissing(syncTargets, CliType.CODEX, false) || changed;
        changed = putDefaultIfMissing(syncTargets, CliType.GEMINI, false) || changed;
        changed = putDefaultIfMissing(syncTargets, CliType.OPENCODE, false) || changed;
        return changed;
    }

    private static boolean putDefaultIfMissing(Map<CliType, Boolean> map, CliType cliType, boolean defaultValue) {
        if (!map.containsKey(cliType)) {
            map.put(cliType, defaultValue);
            return true;
        }
        return false;
    }

    private static boolean isLegacyRepoPlaceholder(Skill skill) {
        if (skill == null || skill.isInstalled()) {
            return false;
        }
        if (skill.getLocalPath() != null && !skill.getLocalPath().isBlank()) {
            return false;
        }
        String repo = skill.getRepository();
        if (repo == null || repo.isBlank()) {
            return false;
        }
        String desc = skill.getDescription();
        return desc != null
                && desc.startsWith("GitHub: ")
                && repo.contains("github.com/");
    }

    private static boolean isTemporaryInstallArtifact(Skill skill) {
        if (skill == null) {
            return false;
        }

        String localPath = skill.getLocalPath();
        String localFolder = "";
        if (localPath != null && !localPath.isBlank()) {
            String normalized = localPath.replace('\\', '/');
            int index = normalized.lastIndexOf('/');
            localFolder = index >= 0 ? normalized.substring(index + 1) : normalized;
        }

        boolean tempName = isTemporaryInstallDirectoryName(skill.getName());
        boolean tempPath = isTemporaryInstallDirectoryName(localFolder);
        if (!tempName && !tempPath) {
            return false;
        }

        if (localPath != null && !localPath.isBlank()) {
            try {
                Path localDir = Path.of(localPath);
                if (Files.isDirectory(localDir) && Files.isRegularFile(localDir.resolve("SKILL.md"))) {
                    return false;
                }
            } catch (InvalidPathException ignored) {
            }
        }
        return true;
    }

    private static boolean isTemporaryInstallDirectory(Path dir) {
        if (dir == null || dir.getFileName() == null) {
            return false;
        }
        return isTemporaryInstallDirectoryName(dir.getFileName().toString());
    }

    private static boolean isTemporaryInstallDirectoryName(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        return SKILL_TEMP_ROOT_DIR.equals(name)
                || name.startsWith(".repo-install-")
                || name.startsWith(".skill-import-")
                || name.startsWith(".skill-zip-");
    }

    private static List<Path> collectImportSkillDirs(Path root) throws IOException {
        List<Path> candidates = new ArrayList<>();

        Path skillsFolder = root.resolve("skills");
        if (Files.isDirectory(skillsFolder)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(skillsFolder)) {
                for (Path child : stream) {
                    if (Files.isDirectory(child)) {
                        candidates.add(child);
                    }
                }
            }
            if (!candidates.isEmpty()) {
                return candidates;
            }
        }

        if (Files.isRegularFile(root.resolve("SKILL.md"))) {
            candidates.add(root);
            return candidates;
        }

        try (var stream = Files.walk(root, 4)) {
            stream.filter(p -> Files.isRegularFile(p)
                    && "SKILL.md".equalsIgnoreCase(p.getFileName().toString()))
                    .forEach(p -> {
                        Path parent = p.getParent();
                        if (parent != null && !candidates.contains(parent)) {
                            candidates.add(parent);
                        }
                    });
        }
        if (!candidates.isEmpty()) {
            return candidates;
        }

        List<Path> directDirs = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            for (Path child : stream) {
                if (Files.isDirectory(child) && !child.getFileName().toString().startsWith(".")) {
                    directDirs.add(child);
                }
            }
        }
        if (directDirs.size() == 1) {
            candidates.add(directDirs.get(0));
        }
        return candidates;
    }

    private static String resolveImportedSkillName(Path candidate, String zipFileName) {
        String name = candidate.getFileName() != null ? candidate.getFileName().toString() : "";
        if (name.endsWith("-main")) {
            name = name.substring(0, name.length() - 5);
        } else if (name.endsWith("-master")) {
            name = name.substring(0, name.length() - 7);
        }
        if (name.isBlank()) {
            name = stripZipExtension(zipFileName);
        }
        if (name.isBlank()) {
            name = "skill";
        }
        return name;
    }

    private static String stripZipExtension(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "";
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".zip") && fileName.length() > 4) {
            return fileName.substring(0, fileName.length() - 4);
        }
        return fileName;
    }

    private static void upsertImportedSkill(List<Skill> skills, String skillName, Path localDir, String sourceName) {
        Skill existing = skills.stream()
                .filter(skill -> skill != null && skill.getName() != null
                        && skill.getName().equalsIgnoreCase(skillName))
                .findFirst()
                .orElse(null);

        Skill target = existing;
        if (target == null) {
            target = new Skill();
            target.setName(skillName);
            target.setDescription("Imported from ZIP: " + sourceName);
            skills.add(target);
        }
        target.setInstalled(true);
        target.setLocalPath(localDir.toString());
    }

    private static boolean isDirNotEmpty(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return false;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            return stream.iterator().hasNext();
        }
    }

    private Path createSkillTempDir(Path skillsDir, String prefix) throws IOException {
        Path tempRootDir = skillsDir.resolve(SKILL_TEMP_ROOT_DIR);
        Files.createDirectories(tempRootDir);
        return Files.createTempDirectory(tempRootDir, prefix);
    }

    private boolean detectGitAvailable() {
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "--version");
            pb.redirectErrorStream(true);
            process = pb.start();
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private OperationResult installSkillFromGithubZip(RepoRef repoRef, Path targetDir) {
        Path parent = targetDir.getParent();
        if (parent == null) {
            return new OperationResult(false, "Invalid target directory");
        }

        Path tempRoot = null;
        try {
            Files.createDirectories(parent);
            tempRoot = createSkillTempDir(parent, ".skill-zip-");

            String lastError = "Download failed";
            for (String branch : buildBranchCandidates(repoRef)) {
                Path branchExtractDir = tempRoot.resolve(branch);
                Files.createDirectories(branchExtractDir);

                ZipAttemptResult attempt = downloadAndExtractZip(repoRef, branch, branchExtractDir);
                if (!attempt.success()) {
                    lastError = attempt.message();
                    deleteRecursively(branchExtractDir);
                    continue;
                }

                Path extractedRoot = detectZipRootDir(branchExtractDir);
                if (extractedRoot == null) {
                    lastError = "Invalid ZIP structure";
                    deleteRecursively(branchExtractDir);
                    continue;
                }

                if (Files.exists(targetDir)) {
                    deleteRecursively(targetDir);
                }
                Files.move(extractedRoot, targetDir, StandardCopyOption.REPLACE_EXISTING);
                return new OperationResult(true, "Installed via GitHub ZIP");
            }

            return new OperationResult(false, lastError);
        } catch (Exception e) {
            LOG.warn("Failed to install skill via zip", e);
            return new OperationResult(false, e.getMessage());
        } finally {
            if (tempRoot != null) {
                try {
                    deleteRecursively(tempRoot);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private List<String> buildBranchCandidates(RepoRef repoRef) {
        List<String> branches = new ArrayList<>();
        addBranchCandidate(branches, fetchDefaultBranch(repoRef));
        addBranchCandidate(branches, "main");
        addBranchCandidate(branches, "master");
        return branches;
    }

    private static void addBranchCandidate(List<String> branches, String branch) {
        if (branch == null || branch.isBlank()) {
            return;
        }
        if (!branches.contains(branch)) {
            branches.add(branch);
        }
    }

    private String fetchDefaultBranch(RepoRef repoRef) {
        HttpURLConnection conn = null;
        try {
            String apiUrl = "https://api.github.com/repos/" + repoRef.owner() + "/" + repoRef.repoName();
            conn = openGetConnection(apiUrl);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                return null;
            }
            try (InputStream in = conn.getInputStream();
                    InputStreamReader reader = new InputStreamReader(in)) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                if (root.has("default_branch") && !root.get("default_branch").isJsonNull()) {
                    String value = root.get("default_branch").getAsString();
                    return value == null || value.isBlank() ? null : value.trim();
                }
            }
        } catch (Exception e) {
            LOG.debug("Failed to query default branch: " + e.getMessage());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
        return null;
    }

    private ZipAttemptResult downloadAndExtractZip(RepoRef repoRef, String branch, Path extractDir) {
        HttpURLConnection conn = null;
        try {
            String zipUrl = "https://codeload.github.com/" + repoRef.owner() + "/" + repoRef.repoName()
                    + "/zip/refs/heads/" + branch;
            conn = openZipConnection(zipUrl);
            int code = conn.getResponseCode();
            if (code == 404) {
                return new ZipAttemptResult(false, true, "Branch not found: " + branch);
            }
            if (code < 200 || code >= 300) {
                String detail = readErrorPreview(conn);
                return new ZipAttemptResult(false, false,
                        detail == null || detail.isBlank() ? "HTTP " + code : "HTTP " + code + " - " + detail);
            }

            try (InputStream in = conn.getInputStream()) {
                extractZip(in, extractDir);
            }
            return new ZipAttemptResult(true, false, "OK");
        } catch (Exception e) {
            return new ZipAttemptResult(false, false, e.getMessage());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private HttpURLConnection openGetConnection(String url) throws IOException {
        HttpURLConnection conn = openHttpConnection(url);
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(15_000);
        conn.setRequestProperty("User-Agent", "coding-switch-plugin");
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        applyGitHubAuthHeader(conn);
        return conn;
    }

    private HttpURLConnection openZipConnection(String url) throws IOException {
        HttpURLConnection conn = openHttpConnection(url);
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(60_000);
        conn.setRequestProperty("User-Agent", "coding-switch-plugin");
        conn.setRequestProperty("Accept", "*/*");
        applyGitHubAuthHeader(conn);
        return conn;
    }

    private static HttpURLConnection openHttpConnection(String url) throws IOException {
        try {
            return (HttpURLConnection) URI.create(url).toURL().openConnection();
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid URL: " + url, e);
        }
    }

    private void applyGitHubAuthHeader(HttpURLConnection conn) {
        String token = PluginSettings.getInstance().getGithubToken();
        if (token == null || token.isBlank()) {
            return;
        }
        conn.setRequestProperty("Authorization", "Bearer " + token);
    }

    private static boolean isRateLimitResponse(int code, HttpURLConnection conn, String detail) {
        if (code != 403 && code != 429) {
            return false;
        }
        RateLimitInfo info = parseRateLimitInfo(conn, detail);
        if (info.remaining() != null && info.remaining() <= 0) {
            return true;
        }
        if (info.retryAfterSeconds() != null && info.retryAfterSeconds() > 0) {
            return true;
        }
        return isRateLimitMessage(info.message());
    }

    private static RateLimitInfo parseRateLimitInfo(HttpURLConnection conn, String detail) {
        Integer remaining = parseIntegerHeader(conn, "x-ratelimit-remaining");
        Long resetEpoch = parseLongHeader(conn, "x-ratelimit-reset");
        Integer retryAfter = parseIntegerHeader(conn, "retry-after");
        return new RateLimitInfo(remaining, resetEpoch, retryAfter, detail);
    }

    private static Integer parseIntegerHeader(HttpURLConnection conn, String header) {
        if (conn == null || header == null || header.isBlank()) {
            return null;
        }
        try {
            String value = conn.getHeaderField(header);
            if (value == null || value.isBlank()) {
                return null;
            }
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static Long parseLongHeader(HttpURLConnection conn, String header) {
        if (conn == null || header == null || header.isBlank()) {
            return null;
        }
        try {
            String value = conn.getHeaderField(header);
            if (value == null || value.isBlank()) {
                return null;
            }
            return Long.parseLong(value.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static String buildRateLimitMessage(HttpURLConnection conn, String detail) {
        RateLimitInfo info = parseRateLimitInfo(conn, detail);
        StringBuilder sb = new StringBuilder("GitHub API rate limit exceeded");
        if (info.resetEpochSecond() != null && info.resetEpochSecond() > 0) {
            String reset = Instant.ofEpochSecond(info.resetEpochSecond())
                    .atZone(ZoneId.systemDefault())
                    .format(RATE_LIMIT_TIME_FORMAT);
            sb.append("; reset at ").append(reset);
        } else if (info.retryAfterSeconds() != null && info.retryAfterSeconds() > 0) {
            sb.append("; retry after ").append(info.retryAfterSeconds()).append("s");
        }
        String githubToken = PluginSettings.getInstance().getGithubToken();
        if (githubToken == null || githubToken.isBlank()) {
            sb.append("; configure GitHub token in Settings");
        }
        if (detail != null && !detail.isBlank()) {
            sb.append("; ").append(detail);
        }
        return sb.toString();
    }

    private static boolean isRateLimitMessage(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("rate limit")
                || normalized.contains("secondary rate limit")
                || normalized.contains("api rate limit exceeded");
    }

    private static String readErrorPreview(HttpURLConnection conn) {
        if (conn == null) {
            return "";
        }
        try (InputStream in = conn.getErrorStream()) {
            if (in == null) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                    if (sb.length() > 200) {
                        break;
                    }
                }
            }
            String text = sb.toString();
            return text.length() > 200 ? text.substring(0, 200) + "..." : text;
        } catch (Exception e) {
            return "";
        }
    }

    private static void extractZip(InputStream in, Path extractDir) throws IOException {
        Files.createDirectories(extractDir);
        try (ZipInputStream zipIn = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zipIn.getNextEntry()) != null) {
                Path out = extractDir.resolve(entry.getName()).normalize();
                if (!out.startsWith(extractDir)) {
                    throw new IOException("Invalid zip entry path");
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Path parent = out.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    Files.copy(zipIn, out, StandardCopyOption.REPLACE_EXISTING);
                }
                zipIn.closeEntry();
            }
        }
    }

    private static Path detectZipRootDir(Path extractDir) throws IOException {
        List<Path> children = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(extractDir)) {
            for (Path child : stream) {
                children.add(child);
            }
        }
        if (children.size() == 1 && Files.isDirectory(children.get(0))) {
            return children.get(0);
        }
        for (Path child : children) {
            if (Files.isDirectory(child)) {
                return child;
            }
        }
        return null;
    }

    private OperationResult runGit(Path workDir, String... command) {
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(workDir.toFile());
            pb.redirectErrorStream(true);
            process = pb.start();

            boolean finished = process.waitFor(90, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new OperationResult(false, "git command timeout");
            }

            StringBuilder out = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    out.append(line).append('\n');
                }
            }

            if (process.exitValue() != 0) {
                String msg = out.toString().trim();
                if (msg.length() > 300) {
                    msg = msg.substring(0, 300) + "...";
                }
                return new OperationResult(false, msg.isBlank() ? "git command failed" : msg);
            }
            return new OperationResult(true, out.toString().trim());
        } catch (Exception e) {
            if (command.length > 0 && "git".equalsIgnoreCase(command[0])) {
                String msg = e.getMessage();
                if (msg != null && msg.toLowerCase(Locale.ROOT).contains("cannot run program")) {
                    gitAvailableCached = false;
                    gitLastCheckedAt = System.currentTimeMillis();
                }
            }
            return new OperationResult(false, e.getMessage());
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    // =====================================================================
    // 内部工具
    // =====================================================================

    private void saveSkills(List<Skill> skills) {
        State activeState = getActiveState();
        activeState.skillsJson = GSON.toJson(skills);
        saveActiveState(activeState);
        fireChanged();
    }

    public State snapshotCurrentState() {
        State snapshot = new State();
        snapshot.skillsJson = GSON.toJson(getSkills());
        snapshot.customRepoConfigsJson = GSON.toJson(toCustomRepoConfigs(getCustomRepoOptions()));
        snapshot.customReposJson = "[]";
        return normalizeState(snapshot);
    }

    public State snapshotLocalState() {
        return normalizeState(myState);
    }

    public State snapshotSharedState() {
        return readSharedState(new State());
    }

    public void overwriteLocalState(State state) {
        myState = normalizeState(state);
    }

    public void writeSharedState(State state) {
        PluginDataStorage.writeJson(PluginDataStorage.getSharedSkillsPath(), normalizeState(state));
    }

    public void notifyStateChanged() {
        fireChanged();
    }

    private State getActiveState() {
        if (PluginSettings.getInstance().getStorageMode() == PluginSettings.DataStorageMode.USER_SHARED) {
            return readSharedState(normalizeState(myState));
        }
        return myState;
    }

    private void saveActiveState(State state) {
        State normalized = normalizeState(state);
        if (PluginSettings.getInstance().getStorageMode() == PluginSettings.DataStorageMode.USER_SHARED) {
            writeSharedState(normalized);
        } else {
            myState = normalized;
        }
    }

    private State readSharedState(State defaultState) {
        return normalizeState(PluginDataStorage.readJson(
                PluginDataStorage.getSharedSkillsPath(),
                State.class,
                normalizeState(defaultState)));
    }

    private static State normalizeState(State state) {
        State normalized = state == null ? new State() : state;
        if (normalized.skillsJson == null || normalized.skillsJson.isBlank()) {
            normalized.skillsJson = "[]";
        }
        if (normalized.customRepoConfigsJson == null || normalized.customRepoConfigsJson.isBlank()) {
            normalized.customRepoConfigsJson = "[]";
        }
        if (normalized.customReposJson == null || normalized.customReposJson.isBlank()) {
            normalized.customReposJson = "[]";
        }
        return normalized;
    }

    public void addChangeListener(Runnable listener) {
        changeListeners.add(listener);
    }

    private void fireChanged() {
        for (Runnable listener : changeListeners) {
            listener.run();
        }
    }
}
