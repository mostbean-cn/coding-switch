package com.github.nicestudent.codingswitch.model;

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
    private String repository; // GitHub 仓库 URL
    private String path; // 仓库内的子路径
    private boolean installed;
    private String localPath; // 本地安装路径

    public Skill() {
        this.id = UUID.randomUUID().toString();
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
