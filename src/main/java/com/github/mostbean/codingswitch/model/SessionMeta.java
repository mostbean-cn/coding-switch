package com.github.mostbean.codingswitch.model;

/**
 * 会话元信息。
 * 对应 cc-switch 的 SessionMeta 数据结构，描述一个 AI CLI 会话的基本信息。
 */
public class SessionMeta {

    private String providerId;
    private String sessionId;
    private String title;
    private String summary;
    private String projectDir;
    private Long createdAt;
    private Long lastActiveAt;
    private String sourcePath;
    private String resumeCommand;

    public SessionMeta() {
    }

    public SessionMeta(String providerId, String sessionId) {
        this.providerId = providerId;
        this.sessionId = sessionId;
    }

    // ---- Getters & Setters ----

    public String getProviderId() {
        return providerId;
    }

    public void setProviderId(String providerId) {
        this.providerId = providerId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getProjectDir() {
        return projectDir;
    }

    public void setProjectDir(String projectDir) {
        this.projectDir = projectDir;
    }

    public Long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }

    public Long getLastActiveAt() {
        return lastActiveAt;
    }

    public void setLastActiveAt(Long lastActiveAt) {
        this.lastActiveAt = lastActiveAt;
    }

    public String getSourcePath() {
        return sourcePath;
    }

    public void setSourcePath(String sourcePath) {
        this.sourcePath = sourcePath;
    }

    public String getResumeCommand() {
        return resumeCommand;
    }

    public void setResumeCommand(String resumeCommand) {
        this.resumeCommand = resumeCommand;
    }

    /**
     * 返回用于排序的有效时间戳（优先使用 lastActiveAt，其次 createdAt）。
     */
    public long getEffectiveTimestamp() {
        if (lastActiveAt != null)
            return lastActiveAt;
        if (createdAt != null)
            return createdAt;
        return 0L;
    }

    /**
     * 返回显示用的标题（优先 title，其次 projectDir 的最后一段，最后 sessionId 前8位）。
     */
    public String getDisplayTitle() {
        if (title != null && !title.isBlank())
            return title;
        if (projectDir != null && !projectDir.isBlank()) {
            String normalized = projectDir.replaceAll("[/\\\\]+$", "");
            int lastSep = Math.max(normalized.lastIndexOf('/'), normalized.lastIndexOf('\\'));
            return lastSep >= 0 ? normalized.substring(lastSep + 1) : normalized;
        }
        return sessionId != null && sessionId.length() > 8
                ? sessionId.substring(0, 8)
                : sessionId;
    }
}
