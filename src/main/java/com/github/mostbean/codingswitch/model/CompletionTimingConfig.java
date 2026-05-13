package com.github.mostbean.codingswitch.model;

import java.util.Objects;

public class CompletionTimingConfig {

    private int debounceDelayMs = 650;
    private int manualCooldownMs = 350;
    private int streamRenderThrottleMs = 45;
    private int inFlightRetryDelayMs = 300;
    private int maxPromptTokens = 1500;

    public CompletionTimingConfig() {
    }

    public CompletionTimingConfig copy() {
        CompletionTimingConfig copy = new CompletionTimingConfig();
        copy.setDebounceDelayMs(debounceDelayMs);
        copy.setManualCooldownMs(manualCooldownMs);
        copy.setStreamRenderThrottleMs(streamRenderThrottleMs);
        copy.setInFlightRetryDelayMs(inFlightRetryDelayMs);
        copy.setMaxPromptTokens(maxPromptTokens);
        return copy;
    }

    public int getDebounceDelayMs() {
        return debounceDelayMs;
    }

    public void setDebounceDelayMs(int debounceDelayMs) {
        this.debounceDelayMs = Math.max(100, Math.min(2000, debounceDelayMs));
    }

    public int getManualCooldownMs() {
        return manualCooldownMs;
    }

    public void setManualCooldownMs(int manualCooldownMs) {
        this.manualCooldownMs = Math.max(100, Math.min(1000, manualCooldownMs));
    }

    public int getStreamRenderThrottleMs() {
        return streamRenderThrottleMs;
    }

    public void setStreamRenderThrottleMs(int streamRenderThrottleMs) {
        this.streamRenderThrottleMs = Math.max(16, Math.min(200, streamRenderThrottleMs));
    }

    public int getInFlightRetryDelayMs() {
        return inFlightRetryDelayMs;
    }

    public void setInFlightRetryDelayMs(int inFlightRetryDelayMs) {
        this.inFlightRetryDelayMs = Math.max(100, Math.min(1000, inFlightRetryDelayMs));
    }

    public int getMaxPromptTokens() {
        return maxPromptTokens;
    }

    public void setMaxPromptTokens(int maxPromptTokens) {
        this.maxPromptTokens = Math.max(256, Math.min(4096, maxPromptTokens));
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CompletionTimingConfig that)) {
            return false;
        }
        return debounceDelayMs == that.debounceDelayMs
            && manualCooldownMs == that.manualCooldownMs
            && streamRenderThrottleMs == that.streamRenderThrottleMs
            && inFlightRetryDelayMs == that.inFlightRetryDelayMs
            && maxPromptTokens == that.maxPromptTokens;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
            debounceDelayMs,
            manualCooldownMs,
            streamRenderThrottleMs,
            inFlightRetryDelayMs,
            maxPromptTokens
        );
    }
}
