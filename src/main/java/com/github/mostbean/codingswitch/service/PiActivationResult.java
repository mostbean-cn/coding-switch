package com.github.mostbean.codingswitch.service;

public final class PiActivationResult {

    public enum AuthSwitchState {
        NOT_APPLICABLE,
        SNAPSHOT_RESTORED,
        LOGIN_REQUIRED,
        SNAPSHOT_INVALID
    }

    private final AuthSwitchState authSwitchState;

    private PiActivationResult(AuthSwitchState authSwitchState) {
        this.authSwitchState = authSwitchState;
    }

    public static PiActivationResult notApplicable() {
        return new PiActivationResult(AuthSwitchState.NOT_APPLICABLE);
    }

    public static PiActivationResult snapshotRestored() {
        return new PiActivationResult(AuthSwitchState.SNAPSHOT_RESTORED);
    }

    public static PiActivationResult loginRequired() {
        return new PiActivationResult(AuthSwitchState.LOGIN_REQUIRED);
    }

    public static PiActivationResult snapshotInvalid() {
        return new PiActivationResult(AuthSwitchState.SNAPSHOT_INVALID);
    }

    public AuthSwitchState getAuthSwitchState() {
        return authSwitchState;
    }
}
