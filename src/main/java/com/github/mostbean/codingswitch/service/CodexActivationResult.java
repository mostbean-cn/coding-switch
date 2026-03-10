package com.github.mostbean.codingswitch.service;

public final class CodexActivationResult {

    public enum AuthSwitchState {
        NOT_APPLICABLE,
        SNAPSHOT_RESTORED,
        LOGIN_REQUIRED,
        SNAPSHOT_INVALID
    }

    private final AuthSwitchState authSwitchState;

    private CodexActivationResult(AuthSwitchState authSwitchState) {
        this.authSwitchState = authSwitchState;
    }

    public static CodexActivationResult notApplicable() {
        return new CodexActivationResult(AuthSwitchState.NOT_APPLICABLE);
    }

    public static CodexActivationResult snapshotRestored() {
        return new CodexActivationResult(AuthSwitchState.SNAPSHOT_RESTORED);
    }

    public static CodexActivationResult loginRequired() {
        return new CodexActivationResult(AuthSwitchState.LOGIN_REQUIRED);
    }

    public static CodexActivationResult snapshotInvalid() {
        return new CodexActivationResult(AuthSwitchState.SNAPSHOT_INVALID);
    }

    public AuthSwitchState getAuthSwitchState() {
        return authSwitchState;
    }
}
