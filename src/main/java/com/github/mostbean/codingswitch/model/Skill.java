package com.github.mostbean.codingswitch.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Skill 数据模型。
 * 代表一个可安装到 ~/.claude/skills/ 的技能。
 */
public class Skill {

    public enum Kind {
        SINGLE,
        REPOSITORY
    }

    public static class SkillChild {
        private String name;
        private String relativePath;
        private String localPath;
        private boolean installed;
        private Boolean owned;
        private Map<CliType, Boolean> syncTargets;

        public SkillChild() {
            this.syncTargets = createDefaultSyncTargets();
        }

        public SkillChild(String name, String relativePath, String localPath, boolean installed) {
            this(name, relativePath, localPath, installed, true);
        }

        public SkillChild(String name, String relativePath, String localPath, boolean installed, boolean owned) {
            this.name = name;
            this.relativePath = relativePath;
            this.localPath = localPath;
            this.installed = installed;
            this.owned = owned;
            this.syncTargets = createDefaultSyncTargets();
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getRelativePath() {
            return relativePath;
        }

        public void setRelativePath(String relativePath) {
            this.relativePath = relativePath;
        }

        public String getLocalPath() {
            return localPath;
        }

        public void setLocalPath(String localPath) {
            this.localPath = localPath;
        }

        public boolean isInstalled() {
            return installed;
        }

        public void setInstalled(boolean installed) {
            this.installed = installed;
        }

        public boolean isOwned() {
            return !Boolean.FALSE.equals(owned);
        }

        public void setOwned(boolean owned) {
            this.owned = owned;
        }

        public Boolean getOwned() {
            return owned;
        }

        public Map<CliType, Boolean> getSyncTargets() {
            return syncTargets;
        }

        public void setSyncTargets(Map<CliType, Boolean> syncTargets) {
            this.syncTargets = syncTargets;
        }

        public boolean isSyncedTo(CliType cliType) {
            return syncTargets != null && Boolean.TRUE.equals(syncTargets.get(cliType));
        }

        public void setSyncedTo(CliType cliType, boolean synced) {
            if (syncTargets == null) {
                syncTargets = createDefaultSyncTargets();
            }
            syncTargets.put(cliType, synced);
        }
    }

    private String id;
    private Kind kind;
    private String name;
    private String description;
    private String repository;
    private String branch;
    private String path;
    private boolean installed;
    private String localPath;
    private List<SkillChild> children;

    /** 该 Skill 同步到哪些 CLI（Claude 原生支持，其他 CLI 通过 Prompt Bridge 适配） */
    private Map<CliType, Boolean> syncTargets;

    public Skill() {
        this.id = UUID.randomUUID().toString();
        this.kind = Kind.SINGLE;
        this.children = new ArrayList<>();
        this.syncTargets = createDefaultSyncTargets();
    }

    public Skill(String name, String description, String repository, String path) {
        this();
        this.name = name;
        this.description = description;
        this.repository = repository;
        this.path = path;
    }

    // --- Getters & Setters ---

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Kind getKind() {
        return kind;
    }

    public void setKind(Kind kind) {
        this.kind = kind;
    }

    public boolean isRepositoryPackage() {
        return kind == Kind.REPOSITORY;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getRepository() {
        return repository;
    }

    public void setRepository(String repository) {
        this.repository = repository;
    }

    public String getBranch() {
        return branch;
    }

    public void setBranch(String branch) {
        this.branch = branch;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public boolean isInstalled() {
        return installed;
    }

    public void setInstalled(boolean installed) {
        this.installed = installed;
    }

    public String getLocalPath() {
        return localPath;
    }

    public void setLocalPath(String localPath) {
        this.localPath = localPath;
    }

    public List<SkillChild> getChildren() {
        return children;
    }

    public void setChildren(List<SkillChild> children) {
        this.children = children;
    }

    public Map<CliType, Boolean> getSyncTargets() {
        return syncTargets;
    }

    public void setSyncTargets(Map<CliType, Boolean> syncTargets) {
        this.syncTargets = syncTargets;
    }

    public boolean isSyncedTo(CliType cliType) {
        return syncTargets != null && Boolean.TRUE.equals(syncTargets.get(cliType));
    }

    public void setSyncedTo(CliType cliType, boolean synced) {
        if (syncTargets == null)
            syncTargets = createDefaultSyncTargets();
        syncTargets.put(cliType, synced);
    }

    private static Map<CliType, Boolean> createDefaultSyncTargets() {
        Map<CliType, Boolean> targets = new HashMap<>();
        // 默认均不启用，用户手动勾选后生效
        targets.put(CliType.CLAUDE, false);
        targets.put(CliType.CODEX, false);
        targets.put(CliType.GEMINI, false);
        targets.put(CliType.OPENCODE, false);
        return targets;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        Skill skill = (Skill) o;
        return Objects.equals(id, skill.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return name;
    }
}
