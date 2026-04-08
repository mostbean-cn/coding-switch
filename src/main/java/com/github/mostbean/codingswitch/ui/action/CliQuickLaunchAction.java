package com.github.mostbean.codingswitch.ui.action;

import com.github.mostbean.codingswitch.service.I18n;
import com.github.mostbean.codingswitch.service.PluginSettings;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.terminal.ui.TerminalWidget;
import org.jetbrains.plugins.terminal.TerminalToolWindowManager;
import org.jetbrains.annotations.NotNull;

/**
 * CLI Quick Launch 主执行 Action：点击图标在终端中执行当前选中的 CLI 命令。
 */
public class CliQuickLaunchAction extends DumbAwareAction {

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Presentation presentation = e.getPresentation();
        PluginSettings settings = PluginSettings.getInstance();
        boolean enabled = settings.isCliQuickLaunchEnabled();
        PluginSettings.CliQuickLaunchItem selectedItem = resolveSelectedItem(settings);

        presentation.setVisible(enabled);
        presentation.setText(I18n.t("cliQuickLaunch.action.text"));
        presentation.setEnabled(enabled && selectedItem != null);
        if (selectedItem != null) {
            presentation.setDescription(
                I18n.t("cliQuickLaunch.execute", selectedItem.name)
            );
        } else {
            presentation.setDescription(I18n.t("cliQuickLaunch.noCommand"));
        }
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        PluginSettings settings = PluginSettings.getInstance();
        PluginSettings.CliQuickLaunchItem selectedItem = resolveSelectedItem(settings);
        if (selectedItem == null) {
            return;
        }

        Project project = e.getData(CommonDataKeys.PROJECT);
        if (project == null) {
            return;
        }

        String workingDir = project.getBasePath() != null
            ? project.getBasePath()
            : System.getProperty("user.home");
        executeInTerminal(project, workingDir, selectedItem);
    }

    private static PluginSettings.CliQuickLaunchItem resolveSelectedItem(
        PluginSettings settings
    ) {
        String selectedCommand = settings.getCliQuickLaunchSelectedCommand();
        if (selectedCommand == null || selectedCommand.isEmpty()) {
            return null;
        }
        for (PluginSettings.CliQuickLaunchItem item : settings.getCliQuickLaunchItems()) {
            if (item.command.equals(selectedCommand)) {
                return item;
            }
        }
        return null;
    }

    /**
     * 在 IDE 内置终端中执行命令。
     */
    private void executeInTerminal(
        Project project,
        String workingDir,
        PluginSettings.CliQuickLaunchItem item
    ) {
        TerminalToolWindowManager terminalManager =
            TerminalToolWindowManager.getInstance(project);
        try {
            TerminalWidget terminalWidget = terminalManager.createShellWidget(
                workingDir,
                item.name,
                true,
                true
            );
            terminalWidget.sendCommandToExecute(item.command);

            ToolWindow toolWindow = terminalManager.getToolWindow();
            if (toolWindow != null) {
                toolWindow.activate(terminalWidget::requestFocus, true, true);
            } else {
                terminalWidget.requestFocus();
            }
        } catch (RuntimeException ex) {
            showExecutionError(ex.getMessage());
        }
    }

    private void showExecutionError(String message) {
        Messages.showErrorDialog(
            message == null || message.isBlank() ? I18n.t("cliQuickLaunch.noCommand") : message,
            I18n.t("cliQuickLaunch.action.text")
        );
    }
}
