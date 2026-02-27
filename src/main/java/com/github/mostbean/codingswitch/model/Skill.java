package com.github.mostbean.codingswitch.model;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Skill 数据模型。
 * 代表一个可安装到 ~/.claude/skills/ 的技能。
 */
public class Skill {

    private String id;
    private String name;
    private String description;
    private String repository;
    private String path;
    private boolean installed;
    private String localPath;

    /** 该 Skill 同步到哪些 CLI（目前只有 Claude 真正支持） */
    private Map<CliType, Boolean> syncTargets;

    public Skill() {
        this.id = UUID.randomUUID().toString();
        this.syncTargets = new HashMap<>();
        // 默认仅 Claude 启用
        syncTargets.put(CliType.CLAUDE, true);
        syncTargets.put(CliType.CODEX, false);
        syncTargets.put(CliType.GEMINI, false);
        syncTargets.put(CliType.OPENCODE, false);
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
            syncTargets = new HashMap<>();
        syncTargets.put(cliType, synced);
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
