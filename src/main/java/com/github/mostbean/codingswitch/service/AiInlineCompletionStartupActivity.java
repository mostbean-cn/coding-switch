package com.github.mostbean.codingswitch.service;

import com.intellij.ide.DataManager;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.IdeActions;
import com.intellij.openapi.editor.Caret;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.actionSystem.EditorActionHandler;
import com.intellij.openapi.editor.actionSystem.EditorActionManager;
import com.intellij.openapi.actionSystem.KeyboardShortcut;
import com.intellij.openapi.actionSystem.Shortcut;
import com.intellij.openapi.keymap.Keymap;
import com.intellij.openapi.keymap.KeymapManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import javax.swing.KeyStroke;
import java.awt.Component;
import java.awt.KeyboardFocusManager;
import java.awt.event.KeyEvent;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class AiInlineCompletionStartupActivity implements StartupActivity.DumbAware {

    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    @Override
    public void runActivity(@NotNull Project project) {
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }
        EditorActionManager actionManager = EditorActionManager.getInstance();
        EditorActionHandler originalTabHandler = actionManager.getActionHandler(IdeActions.ACTION_EDITOR_TAB);
        EditorActionHandler originalEnterHandler = actionManager.getActionHandler(IdeActions.ACTION_EDITOR_ENTER);
        actionManager.setActionHandler(
            IdeActions.ACTION_EDITOR_TAB,
            new AcceptInlineCompletionTabHandler(originalTabHandler)
        );
        actionManager.setActionHandler(
            IdeActions.ACTION_EDITOR_ENTER,
            new ScheduleInlineCompletionEnterHandler(originalEnterHandler)
        );
        installAcceptLineKeyDispatcher();
        syncManualCompletionShortcut();
    }

    private void installAcceptLineKeyDispatcher() {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(event -> {
            Component component = event.getComponent();
            if (!isAcceptLineKey(event) || component == null) {
                return false;
            }
            DataContext dataContext = DataManager.getInstance().getDataContext(component);
            Project project = dataContext.getData(CommonDataKeys.PROJECT);
            Editor editor = dataContext.getData(CommonDataKeys.EDITOR);
            if (project == null || editor == null || !AiInlineCompletionService.getInstance().hasActiveCompletion(editor)) {
                return false;
            }
            return AiInlineCompletionService.getInstance().acceptLine(project, editor);
        });
    }

    private boolean isAcceptLineKey(KeyEvent event) {
        return event.getID() == KeyEvent.KEY_PRESSED
            && event.getKeyCode() == KeyEvent.VK_DOWN
            && event.isControlDown()
            && !event.isAltDown()
            && !event.isShiftDown()
            && !event.isMetaDown();
    }

    private static boolean shouldScheduleAutoCompletion() {
        AiFeatureSettings settings = AiFeatureSettings.getInstance();
        return settings.isCodeCompletionEnabled() && settings.isAutoCompletionEnabled();
    }

    private void syncManualCompletionShortcut() {
        String shortcutText = normalizeShortcutText(AiFeatureSettings.getInstance().snapshot().manualCompletionShortcut);
        KeyStroke keyStroke = KeyStroke.getKeyStroke(shortcutText);
        if (keyStroke == null) {
            return;
        }
        Keymap keymap = KeymapManager.getInstance().getActiveKeymap();
        KeyboardShortcut targetShortcut = new KeyboardShortcut(keyStroke, null);
        for (String actionId : keymap.getActionIdList(targetShortcut)) {
            if (!AiFeatureSettings.MANUAL_COMPLETION_ACTION_ID.equals(actionId)) {
                keymap.removeShortcut(actionId, targetShortcut);
            }
        }
        boolean exists = false;
        for (Shortcut shortcut : keymap.getShortcuts(AiFeatureSettings.MANUAL_COMPLETION_ACTION_ID)) {
            if (targetShortcut.equals(shortcut)) {
                exists = true;
                break;
            }
        }
        if (!exists) {
            keymap.addShortcut(AiFeatureSettings.MANUAL_COMPLETION_ACTION_ID, targetShortcut);
        }
    }

    private String normalizeShortcutText(String shortcutText) {
        if (shortcutText == null) {
            return "";
        }
        String normalized = shortcutText.trim()
            .replace("+", " ")
            .replaceAll("\\s+", " ");
        String[] parts = normalized.split(" ");
        if (parts.length != 2) {
            return normalized;
        }
        String modifier = switch (parts[0].toLowerCase()) {
            case "ctrl", "control" -> "control";
            case "alt" -> "alt";
            case "shift" -> "shift";
            case "meta", "cmd", "command" -> "meta";
            default -> parts[0].toLowerCase();
        };
        return modifier + " " + parts[1].toUpperCase();
    }

    private static final class AcceptInlineCompletionTabHandler extends EditorActionHandler {
        private final EditorActionHandler delegate;

        private AcceptInlineCompletionTabHandler(EditorActionHandler delegate) {
            this.delegate = delegate;
        }

        @Override
        protected void doExecute(@NotNull Editor editor, @Nullable Caret caret, DataContext dataContext) {
            Project project = dataContext.getData(CommonDataKeys.PROJECT);
            if (project != null && AiInlineCompletionService.getInstance().acceptAll(project, editor)) {
                return;
            }
            if (delegate != null) {
                delegate.execute(editor, caret, dataContext);
            }
        }

        @Override
        protected boolean isEnabledForCaret(@NotNull Editor editor, @NotNull Caret caret, DataContext dataContext) {
            return AiInlineCompletionService.getInstance().hasActiveCompletion(editor)
                || (delegate != null && delegate.isEnabled(editor, caret, dataContext));
        }
    }

    private static final class ScheduleInlineCompletionEnterHandler extends EditorActionHandler {
        private final EditorActionHandler delegate;

        private ScheduleInlineCompletionEnterHandler(EditorActionHandler delegate) {
            this.delegate = delegate;
        }

        @Override
        protected void doExecute(@NotNull Editor editor, @Nullable Caret caret, DataContext dataContext) {
            if (delegate != null) {
                delegate.execute(editor, caret, dataContext);
            }
            Project project = dataContext.getData(CommonDataKeys.PROJECT);
            if (project != null && shouldScheduleAutoCompletion() && AiCompletionEditorGuard.isEligible(project, editor)) {
                AiInlineCompletionService.getInstance().hide(editor);
                AiInlineCompletionService.getInstance().scheduleAuto(project, editor);
            }
        }

        @Override
        protected boolean isEnabledForCaret(@NotNull Editor editor, @NotNull Caret caret, DataContext dataContext) {
            return delegate == null || delegate.isEnabled(editor, caret, dataContext);
        }
    }
}
