package com.github.mostbean.codingswitch.ui.action;

import com.github.mostbean.codingswitch.service.I18n;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.NotNull;

/**
 * 将当前光标行或选中代码行号的相对路径插入到当前激活终端输入区。
 */
public class InsertFilePathWithLineAction extends DumbAwareAction {

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.EDT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Presentation presentation = e.getPresentation();
        presentation.setText(I18n.t("cliQuickLaunch.insertFilePathWithLine"));

        ActiveFilePathResolver.Resolution resolution = ActiveFilePathResolver.resolveWithLine(e);
        if (!resolution.available()) {
            presentation.setVisible(false);
            presentation.setEnabled(false);
            return;
        }

        Project project = e.getData(CommonDataKeys.PROJECT);
        boolean hasActiveTerminal = TerminalInputService.hasActiveTerminal(project);
        presentation.setVisible(hasActiveTerminal);
        presentation.setEnabled(hasActiveTerminal);
        presentation.setDescription(I18n.t("cliQuickLaunch.insertFilePathWithLine.description"));
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        ActiveFilePathResolver.Resolution resolution = ActiveFilePathResolver.resolveWithLine(e);
        if (!resolution.available() || resolution.pathText() == null) {
            showError(I18n.t(resolution.messageKey()));
            return;
        }

        Project project = e.getData(CommonDataKeys.PROJECT);
        TerminalInputService.InsertResult result = TerminalInputService.insertIntoActiveTerminal(
            project,
            resolution.pathText()
        );
        if (!result.success()) {
            showError(result.message());
        }
    }

    private void showError(@NotNull String message) {
        Messages.showErrorDialog(
            message,
            I18n.t("cliQuickLaunch.insertFilePathWithLine")
        );
    }
}
