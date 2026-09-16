package com.github.mostbean.codingswitch.service;

public final class GrokActivationResult {

    public enum AuthSwitchState {
        NOT_APPLICABLE,
        SNAPSHOT_RESTORED,
        LOGIN_REQUIRED,
        SNAPSHOT_INVALID
    }

    private final AuthSwitchState authSwitchState;

    private GrokActivationResult(AuthSwitchState authSwitchState) {
        this.authSwitchState = authSwitchState;
    }

    public static GrokActivationResult notApplicable() {
        return new GrokActivationResult(AuthSwitchState.NOT_APPLICABLE);
    }

    public static GrokActivationResult snapshotRestored() {
        return new GrokActivationResult(AuthSwitchState.SNAPSHOT_RESTORED);
    }

    public static GrokActivationResult loginRequired() {
        return new GrokActivationResult(AuthSwitchState.LOGIN_REQUIRED);
    }

    public static GrokActivationResult snapshotInvalid() {
        return new GrokActivationResult(AuthSwitchState.SNAPSHOT_INVALID);
    }

    public AuthSwitchState getAuthSwitchState() {
        return authSwitchState;
    }
}
