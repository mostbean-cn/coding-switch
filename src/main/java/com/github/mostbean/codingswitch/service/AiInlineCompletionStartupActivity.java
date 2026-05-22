package com.github.mostbean.codingswitch.service;

import com.intellij.ide.DataManager;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.IdeActions;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Caret;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.actionSystem.EditorActionHandler;
import com.intellij.openapi.editor.actionSystem.EditorActionManager;
import com.intellij.openapi.actionSystem.KeyboardShortcut;
import com.intellij.openapi.actionSystem.Shortcut;
import com.intellij.openapi.keymap.Keymap;
import com.intellij.openapi.keymap.KeymapManager;
import com.intellij.openapi.project.Project;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.KeyboardFocusManager;
import java.awt.event.KeyEvent;
import java.util.concurrent.atomic.AtomicBoolean;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import com.intellij.openapi.startup.ProjectActivity;

public class AiInlineCompletionStartupActivity implements ProjectActivity {

    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);
    private static final String ACTION_EDITOR_INDENT_SELECTION = "EditorIndentSelection";

    @Override
    public Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (!project.isDisposed()) {
                install(project);
            }
        });
        return Unit.INSTANCE;
    }

    private void install(@NotNull Project project) {
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }
        EditorActionManager actionManager = EditorActionManager.getInstance();
        EditorActionHandler originalTabHandler = actionManager.getActionHandler(IdeActions.ACTION_EDITOR_TAB);
        EditorActionHandler originalIndentHandler = actionManager.getActionHandler(ACTION_EDITOR_INDENT_SELECTION);
        EditorActionHandler originalEnterHandler = actionManager.getActionHandler(IdeActions.ACTION_EDITOR_ENTER);
        actionManager.setActionHandler(
            IdeActions.ACTION_EDITOR_TAB,
            new AcceptInlineCompletionTabHandler(originalTabHandler)
        );
        actionManager.setActionHandler(
            ACTION_EDITOR_INDENT_SELECTION,
            new AcceptInlineCompletionTabHandler(originalIndentHandler)
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
            if ((!isAcceptAllKey(event) && !isAcceptLineKey(event) && !isHideInlineCompletionKey(event)) || component == null) {
                return false;
            }
            DataContext dataContext = DataManager.getInstance().getDataContext(component);
            Project project = dataContext.getData(CommonDataKeys.PROJECT);
            Editor editor = resolveEditor(dataContext, component);
            if (editor == null || !AiInlineCompletionService.getInstance().hasActiveCompletion(editor)) {
                return false;
            }
            if (isHideInlineCompletionKey(event)) {
                AiInlineCompletionService.getInstance().hide(editor);
                return true;
            }
            if (isAcceptAllKey(event)) {
                AiInlineCompletionService.getInstance().acceptAll(project, editor);
                return true;
            }
            AiInlineCompletionService.getInstance().acceptLine(project, editor);
            return true;
        });
    }

    private Editor resolveEditor(DataContext dataContext, Component component) {
        Editor editor = dataContext.getData(CommonDataKeys.EDITOR);
        if (editor != null) {
            return editor;
        }
        for (Editor candidate : EditorFactory.getInstance().getAllEditors()) {
            if (candidate.isDisposed()) {
                continue;
            }
            if (isEditorComponent(component, candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private boolean isEditorComponent(Component component, Editor editor) {
        return component == editor.getContentComponent()
            || component == editor.getComponent()
            || SwingUtilities.isDescendingFrom(component, editor.getContentComponent())
            || SwingUtilities.isDescendingFrom(component, editor.getComponent());
    }

    private boolean isAcceptAllKey(KeyEvent event) {
        return event.getID() == KeyEvent.KEY_PRESSED
            && event.getKeyCode() == KeyEvent.VK_TAB
            && !event.isControlDown()
            && !event.isAltDown()
            && !event.isShiftDown()
            && !event.isMetaDown();
    }

    private boolean isAcceptLineKey(KeyEvent event) {
        return event.getID() == KeyEvent.KEY_PRESSED
            && event.getKeyCode() == KeyEvent.VK_DOWN
            && event.isControlDown()
            && !event.isAltDown()
            && !event.isShiftDown()
            && !event.isMetaDown();
    }

    private boolean isHideInlineCompletionKey(KeyEvent event) {
        return event.getID() == KeyEvent.KEY_PRESSED
            && event.getKeyCode() == KeyEvent.VK_ESCAPE
            && !event.isControlDown()
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
            if (AiInlineCompletionService.getInstance().acceptAll(project, editor)) {
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
