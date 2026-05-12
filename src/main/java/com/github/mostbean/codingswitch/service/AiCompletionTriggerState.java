package com.github.mostbean.codingswitch.service;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.util.Key;

public final class AiCompletionTriggerState {

    private static final Key<Boolean> MANUAL_TRIGGER_KEY = Key.create("coding.switch.ai.completion.manual");

    private AiCompletionTriggerState() {
    }

    public static void markManual(Editor editor) {
        if (editor != null) {
            editor.putUserData(MANUAL_TRIGGER_KEY, Boolean.TRUE);
        }
    }

    public static boolean consumeManual(Editor editor) {
        if (editor == null) {
            return false;
        }
        boolean manual = Boolean.TRUE.equals(editor.getUserData(MANUAL_TRIGGER_KEY));
        if (manual) {
            editor.putUserData(MANUAL_TRIGGER_KEY, null);
        }
        return manual;
    }
}
