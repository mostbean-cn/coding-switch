package com.github.mostbean.codingswitch.model;

/**
 * 会话消息。
 * 描述会话中单条对话消息（用户 / AI / 系统 / 工具）。
 */
public class SessionMessage {

    private String role;
    private String content;
    private Long timestamp;

    public SessionMessage() {
    }

    public SessionMessage(String role, String content, Long timestamp) {
        this.role = role;
        this.content = content;
        this.timestamp = timestamp;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Long timestamp) {
        this.timestamp = timestamp;
    }

    /**
     * 返回角色的显示标签。
     */
    public String getRoleLabel() {
        if (role == null)
            return "Unknown";
        return switch (role.toLowerCase()) {
            case "assistant" -> "AI";
            case "user" -> "用户";
            case "system" -> "系统";
            case "tool" -> "工具";
            default -> role;
        };
    }
}
