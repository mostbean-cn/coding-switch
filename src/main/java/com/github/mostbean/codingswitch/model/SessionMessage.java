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

}
