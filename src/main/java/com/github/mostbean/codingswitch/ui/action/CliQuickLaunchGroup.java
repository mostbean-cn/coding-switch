package com.github.mostbean.codingswitch.ui.action;

import com.intellij.ide.ActivityTracker;
import com.github.mostbean.codingswitch.service.I18n;
import com.github.mostbean.codingswitch.service.PluginSettings;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.KeepPopupOnPerform;
import com.intellij.openapi.actionSystem.Separator;
import com.intellij.openapi.actionSystem.Presentation;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.NotNull;

/**
 * CLI Quick Launch 工具栏 Action Group：左侧图标执行当前命令，右侧下拉切换 CLI。
 */
public class CliQuickLaunchGroup extends DefaultActionGroup {

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
        presentation.setEnabled(enabled && selectedItem != null);
        if (!enabled) {
            return;
        }

        String displayName = selectedItem == null
            ? I18n.t("cliQuickLaunch.selectCommand")
            : selectedItem.name;
        String text = displayName.length() > 12
            ? displayName.substring(0, 11) + ".."
            : displayName;
        presentation.setText(text);
        presentation.setDescription(I18n.t("cliQuickLaunch.action.text"));
    }

    @Override
    public AnAction @NotNull [] getChildren(AnActionEvent e) {
        PluginSettings settings = PluginSettings.getInstance();
        List<AnAction> actions = new ArrayList<>();
        actions.add(new CliQuickLaunchAction());
        actions.add(new InsertFilePathAction());

        String selectedCommand = settings.getCliQuickLaunchSelectedCommand();
        for (PluginSettings.CliQuickLaunchItem item : settings.getCliQuickLaunchItems()) {
            if (actions.size() == 2) {
                actions.add(Separator.getInstance());
            }
            boolean isSelected = item.command.equals(selectedCommand);
            actions.add(new SelectCliAction(item, isSelected));
        }
        return actions.toArray(AnAction[]::new);
    }

    private static PluginSettings.CliQuickLaunchItem resolveSelectedItem(
        PluginSettings settings
    ) {
        String selectedCommand = settings.getCliQuickLaunchSelectedCommand();
        for (PluginSettings.CliQuickLaunchItem item : settings.getCliQuickLaunchItems()) {
            if (item.command.equals(selectedCommand)) {
                return item;
            }
        }
        return null;
    }

    /**
     * 选择 CLI 命令的子 Action。
     */
    private static class SelectCliAction extends AnAction {

        private final PluginSettings.CliQuickLaunchItem item;

        SelectCliAction(PluginSettings.CliQuickLaunchItem item, boolean isSelected) {
            super(item.name);
            this.item = item;
            Presentation presentation = getTemplatePresentation();
            presentation.setText(isSelected ? "✓ " + item.name : item.name, false);
            presentation.setKeepPopupOnPerform(KeepPopupOnPerform.Always);
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.BGT;
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setText(
                item.command.equals(PluginSettings.getInstance().getCliQuickLaunchSelectedCommand())
                    ? "✓ " + item.name
                    : item.name,
                false
            );
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            PluginSettings.getInstance().setCliQuickLaunchSelectedCommand(item.command);
            ActivityTracker.getInstance().inc();
        }
    }
}
