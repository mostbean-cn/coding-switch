package com.github.mostbean.codingswitch.model;

import java.util.Objects;
import java.util.UUID;

/**
 * 提示词预设数据模型。
 * 代表一个系统提示词配置，可应用到不同的 AI CLI 工具。
 */
public class PromptPreset {

    private String id;
    private String name;
    private String content;
    private CliType targetCli;
    private boolean active;

    public PromptPreset() {
        this.id = UUID.randomUUID().toString();
    }

    public PromptPreset(String name, String content, CliType targetCli) {
        this();
        this.name = name;
        this.content = content;
        this.targetCli = targetCli;
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

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public CliType getTargetCli() {
        return targetCli;
    }

    public void setTargetCli(CliType targetCli) {
        this.targetCli = targetCli;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        PromptPreset that = (PromptPreset) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return name + " → " + targetCli.getDisplayName();
    }
}
