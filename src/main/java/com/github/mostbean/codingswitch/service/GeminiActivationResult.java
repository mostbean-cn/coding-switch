package com.github.mostbean.codingswitch.service;

/**
 * Gemini 激活结果封装。
 * 用于向用户反馈激活操作的状态。
 */
public final class GeminiActivationResult {

    public enum ActivationState {
        NOT_APPLICABLE,      // 不适用（非 Gemini 或非官方登录）
        SNAPSHOT_RESTORED,   // 成功恢复登录态
        LOGIN_REQUIRED,      // 首次使用或无快照，需要登录
        SNAPSHOT_INVALID     // 历史快照失效，需重新登录
    }

    private final ActivationState activationState;

    private GeminiActivationResult(ActivationState activationState) {
        this.activationState = activationState;
    }

    public static GeminiActivationResult notApplicable() {
        return new GeminiActivationResult(ActivationState.NOT_APPLICABLE);
    }

    public static GeminiActivationResult snapshotRestored() {
        return new GeminiActivationResult(ActivationState.SNAPSHOT_RESTORED);
    }

    public static GeminiActivationResult loginRequired() {
        return new GeminiActivationResult(ActivationState.LOGIN_REQUIRED);
    }

    public static GeminiActivationResult snapshotInvalid() {
        return new GeminiActivationResult(ActivationState.SNAPSHOT_INVALID);
    }

    public ActivationState getActivationState() {
        return activationState;
    }
}
