package com.github.mostbean.codingswitch.ui.action;

import com.github.mostbean.codingswitch.service.I18n;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorModificationUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.terminal.ui.TerminalWidget;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentManager;
import com.jediterm.terminal.TtyConnector;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.terminal.TerminalToolWindowManager;

/**
 * 向当前激活的终端会话写入文本，不追加回车执行。
 */
public final class TerminalInputService {

    private TerminalInputService() {}

    public static boolean hasActiveTerminal(@Nullable Project project) {
        TerminalWidget terminalWidget = getActiveTerminalWidget(project);
        if (terminalWidget == null) {
            return false;
        }
        if (terminalWidget.getTtyConnectorAccessor().getTtyConnector() != null) {
            return true;
        }
        return getBlockTerminalPromptEditor(terminalWidget) != null;
    }

    public static @NotNull InsertResult insertIntoActiveTerminal(
        @Nullable Project project,
        @NotNull String text
    ) {
        TerminalWidget terminalWidget = getActiveTerminalWidget(project);
        if (terminalWidget == null) {
            return InsertResult.failure(I18n.t("cliQuickLaunch.insertFilePath.noActiveTerminal"));
        }

        terminalWidget.requestFocus();
        TtyConnector connector = terminalWidget.getTtyConnectorAccessor().getTtyConnector();
        if (connector != null) {
            return writeViaTtyConnector(connector, text);
        }

        Editor promptEditor = getBlockTerminalPromptEditor(terminalWidget);
        if (promptEditor != null) {
            return writeViaPromptEditor(promptEditor, text);
        }

        return InsertResult.failure(I18n.t("cliQuickLaunch.insertFilePath.noActiveTerminal"));
    }

    private static @NotNull InsertResult writeViaTtyConnector(
        @NotNull TtyConnector connector,
        @NotNull String text
    ) {
        try {
            connector.write(text);
        } catch (IOException error) {
            String detail = error.getMessage();
            return InsertResult.failure(
                detail == null || detail.isBlank()
                    ? I18n.t("cliQuickLaunch.insertFilePath.failed")
                    : I18n.t("cliQuickLaunch.insertFilePath.failedWithReason", detail)
            );
        }
        return InsertResult.succeeded();
    }

    private static @NotNull InsertResult writeViaPromptEditor(
        @NotNull Editor editor,
        @NotNull String text
    ) {
        if (!EditorModificationUtil.checkModificationAllowed(editor)) {
            return InsertResult.failure(I18n.t("cliQuickLaunch.insertFilePath.failed"));
        }

        try {
            editor.getContentComponent().requestFocusInWindow();
            ApplicationManager.getApplication().runWriteAction(
                () -> EditorModificationUtil.typeInStringAtCaretHonorMultipleCarets(editor, text)
            );
        } catch (RuntimeException error) {
            String detail = error.getMessage();
            return InsertResult.failure(
                detail == null || detail.isBlank()
                    ? I18n.t("cliQuickLaunch.insertFilePath.failed")
                    : I18n.t("cliQuickLaunch.insertFilePath.failedWithReason", detail)
            );
        }
        return InsertResult.succeeded();
    }

    private static @Nullable TerminalWidget getActiveTerminalWidget(@Nullable Project project) {
        Content selectedContent = findTerminalContent(project);
        return selectedContent == null
            ? null
            : TerminalToolWindowManager.findWidgetByContent(selectedContent);
    }

    private static @Nullable Content findTerminalContent(@Nullable Project project) {
        if (project == null) {
            return null;
        }

        ToolWindow toolWindow = TerminalToolWindowManager.getInstance(project).getToolWindow();
        if (toolWindow == null) {
            return null;
        }

        ContentManager contentManager = toolWindow.getContentManager();
        return contentManager.getSelectedContent();
    }

    private static @Nullable Editor getBlockTerminalPromptEditor(
        @NotNull TerminalWidget terminalWidget
    ) {
        try {
            Object view = readField(terminalWidget, "view");
            if (view == null) {
                return null;
            }
            Object promptView = invokeNoArg(view, "getPromptView");
            Object controller = invokeNoArg(promptView, "getController");
            Object model = invokeNoArg(controller, "getModel");
            Object editor = invokeNoArg(model, "getEditor");
            return editor instanceof Editor promptEditor ? promptEditor : null;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static @Nullable Object readField(
        @NotNull Object target,
        @NotNull String fieldName
    ) throws ReflectiveOperationException {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }

    private static @Nullable Object invokeNoArg(
        @NotNull Object target,
        @NotNull String methodName
    ) throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        Method method = target.getClass().getMethod(methodName);
        return method.invoke(target);
    }

    public record InsertResult(boolean success, @Nullable String message) {
        private static @NotNull InsertResult succeeded() {
            return new InsertResult(true, null);
        }

        private static @NotNull InsertResult failure(@NotNull String message) {
            return new InsertResult(false, message);
        }
    }
}
