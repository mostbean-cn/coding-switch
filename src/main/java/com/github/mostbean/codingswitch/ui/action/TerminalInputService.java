package com.github.mostbean.codingswitch.ui.action;

import com.github.mostbean.codingswitch.service.I18n;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.jediterm.terminal.TtyConnector;
import com.intellij.terminal.ui.TerminalWidget;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentManager;
import java.io.IOException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.terminal.TerminalToolWindowManager;

/**
 * 向当前激活的终端会话写入文本，不追加回车执行。
 */
public final class TerminalInputService {

    private TerminalInputService() {}

    public static boolean hasActiveTerminal(@Nullable Project project) {
        return getActiveTerminalWidget(project) != null;
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
        if (connector == null) {
            return InsertResult.failure(I18n.t("cliQuickLaunch.insertFilePath.noActiveTerminal"));
        }
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

    private static @Nullable TerminalWidget getActiveTerminalWidget(@Nullable Project project) {
        if (project == null) {
            return null;
        }

        TerminalToolWindowManager terminalManager = TerminalToolWindowManager.getInstance(project);
        ToolWindow toolWindow = terminalManager.getToolWindow();
        if (toolWindow == null || !toolWindow.isVisible()) {
            return null;
        }

        ContentManager contentManager = toolWindow.getContentManager();
        Content selectedContent = contentManager.getSelectedContent();
        return selectedContent == null
            ? null
            : TerminalToolWindowManager.findWidgetByContent(selectedContent);
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
