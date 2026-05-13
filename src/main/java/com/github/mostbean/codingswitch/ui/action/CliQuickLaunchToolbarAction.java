package com.github.mostbean.codingswitch.ui.action;

import com.github.mostbean.codingswitch.service.I18n;
import com.github.mostbean.codingswitch.service.PluginSettings;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionPopupMenu;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.actionSystem.ex.CustomComponentAction;
import com.intellij.openapi.actionSystem.ex.TooltipDescriptionProvider;
import com.intellij.openapi.actionSystem.impl.ActionButton;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.DumbAwareAction;
import java.awt.Component;
import java.awt.Toolkit;
import java.awt.event.MouseEvent;
import javax.swing.JComponent;
import javax.swing.Timer;
import org.jetbrains.annotations.NotNull;

/**
 * 工具栏入口：点击后弹出 CLI 快速启动菜单，但不显示 group 自带的下拉箭头。
 */
public class CliQuickLaunchToolbarAction extends DumbAwareAction
    implements CustomComponentAction, TooltipDescriptionProvider {

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public @NotNull JComponent createCustomComponent(
        @NotNull Presentation presentation,
        @NotNull String place
    ) {
        ActionButton button = new DoubleClickActionButton(
            this,
            presentation,
            place,
            ActionToolbar.DEFAULT_MINIMUM_BUTTON_SIZE
        );
        button.setToolTipText(presentation.getDescription());
        return button;
    }

    @Override
    public void updateCustomComponent(
        @NotNull JComponent component,
        @NotNull Presentation presentation
    ) {
        component.setVisible(presentation.isVisible());
        component.setEnabled(presentation.isEnabled());
        component.setToolTipText(presentation.getDescription());
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Presentation presentation = e.getPresentation();
        PluginSettings settings = PluginSettings.getInstance();
        boolean enabled = settings.isCliQuickLaunchEnabled();
        PluginSettings.CliQuickLaunchItem selectedItem = resolveSelectedItem(settings);

        presentation.setVisible(enabled);
        presentation.setEnabled(enabled);
        if (selectedItem != null) {
            presentation.setDescription(
                I18n.t("cliQuickLaunch.toolbar.description", selectedItem.name)
            );
        } else {
            presentation.setDescription(
                I18n.t("cliQuickLaunch.toolbar.noSelection")
            );
        }
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Component component = e.getInputEvent() == null
            ? null
            : e.getInputEvent().getComponent();
        showPopup(component, e.getPlace());
    }

    private void showPopup(Component component, String place) {
        if (component == null) {
            return;
        }

        ActionPopupMenu popupMenu = ActionManager.getInstance().createActionPopupMenu(
            place,
            new CliQuickLaunchGroup()
        );
        popupMenu.getComponent().show(component, 0, component.getHeight());
    }

    private static int resolveDoubleClickDelay() {
        Object interval = Toolkit.getDefaultToolkit().getDesktopProperty("awt.multiClickInterval");
        return interval instanceof Integer value && value > 0 ? value : 250;
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

    private final class DoubleClickActionButton extends ActionButton {

        private final Timer singleClickTimer;

        private DoubleClickActionButton(
            @NotNull CliQuickLaunchToolbarAction action,
            @NotNull Presentation presentation,
            @NotNull String place,
            @NotNull java.awt.Dimension minimumSize
        ) {
            super(action, presentation, place, minimumSize);
            singleClickTimer = new Timer(resolveDoubleClickDelay(), event -> {
                if (isShowing()) {
                    showPopup(this, place);
                }
            });
            singleClickTimer.setRepeats(false);
        }

        @Override
        protected void performAction(MouseEvent event) {
            if (event.getClickCount() >= 2) {
                singleClickTimer.stop();
                executeSelectedCli();
                return;
            }

            singleClickTimer.restart();
        }

        @Override
        public void removeNotify() {
            singleClickTimer.stop();
            super.removeNotify();
        }

        private void executeSelectedCli() {
            DataContext dataContext = getDataContext();
            Project project = dataContext.getData(CommonDataKeys.PROJECT);
            CliQuickLaunchAction.executeSelectedItem(project);
        }
    }
}
