package com.github.mostbean.codingswitch.ui.panel;

import com.github.mostbean.codingswitch.model.Skill;
import com.github.mostbean.codingswitch.service.SkillService;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.ColoredListCellRenderer;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.components.JBList;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.util.List;

/**
 * Skills 管理面板。
 * 使用 JBList 与 ToolbarDecorator 实现原生化的 UI。
 */
public class SkillPanel extends JPanel {

    private final DefaultListModel<Skill> listModel = new DefaultListModel<>();
    private final JBList<Skill> skillList = new JBList<>(listModel);

    public SkillPanel() {
        setLayout(new BorderLayout());
        setBorder(JBUI.Borders.empty(8));

        add(createListPanel(), BorderLayout.CENTER);

        SkillService.getInstance().addChangeListener(this::refreshList);
        refreshList();
    }

    private JComponent createListPanel() {
        skillList.setCellRenderer(new SkillCellRenderer());
        skillList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        skillList.getEmptyText().setText("No skills installed. Click 'Scan Local' to find them.");

        ToolbarDecorator decorator = ToolbarDecorator.createDecorator(skillList)
                .setRemoveAction(button -> onUninstall())
                .setRemoveActionName("Uninstall Skill")
                .addExtraAction(new AnAction("Scan Local", "Scan ~/.claude/skills/ for installed skills",
                        AllIcons.Actions.Refresh) {
                    @Override
                    public void actionPerformed(@NotNull AnActionEvent e) {
                        onScanLocal();
                    }
                })
                .addExtraAction(
                        new AnAction("Add Repository", "Add a custom GitHub repository URL", AllIcons.General.Add) {
                            @Override
                            public void actionPerformed(@NotNull AnActionEvent e) {
                                onAddRepo();
                            }
                        });

        decorator.disableAddAction();
        decorator.disableUpDownActions();

        return decorator.createPanel();
    }

    // =====================================================================
    // 操作方法
    // =====================================================================

    private void onScanLocal() {
        List<Skill> localSkills = SkillService.getInstance().scanLocalSkills();
        listModel.clear();
        for (Skill skill : localSkills) {
            listModel.addElement(skill);
        }
        if (localSkills.isEmpty()) {
            Messages.showInfoMessage(
                    "No skills found in ~/.claude/skills/",
                    "Scan Result");
        }
    }

    private void onUninstall() {
        Skill selected = skillList.getSelectedValue();
        if (selected == null || !selected.isInstalled())
            return;
        int result = Messages.showYesNoDialog(
                "Uninstall skill \"" + selected.getName() + "\"?\nThis will delete the local directory.",
                "Confirm Uninstall", Messages.getWarningIcon());
        if (result == Messages.YES) {
            try {
                SkillService.getInstance().uninstallSkill(selected.getId());
                onScanLocal();
            } catch (IOException ex) {
                Messages.showErrorDialog("Uninstall failed: " + ex.getMessage(), "Error");
            }
        }
    }

    private void onAddRepo() {
        String url = Messages.showInputDialog(
                "Enter GitHub repository URL:\n(e.g., https://github.com/anthropics/courses)",
                "Add Custom Repository", Messages.getQuestionIcon());
        if (url != null && !url.isBlank()) {
            SkillService.getInstance().addCustomRepo(url.trim());
            Messages.showInfoMessage("Repository added: " + url, "Success");
        }
    }

    private void refreshList() {
        listModel.clear();
        for (Skill skill : SkillService.getInstance().getInstalledSkills()) {
            listModel.addElement(skill);
        }
    }

    // =====================================================================
    // 渲染器：使用 ColoredListCellRenderer 支持 append()
    // =====================================================================

    private static class SkillCellRenderer extends ColoredListCellRenderer<Skill> {
        @Override
        protected void customizeCellRenderer(@NotNull JList<? extends Skill> list, Skill skill,
                int index, boolean selected, boolean hasFocus) {
            setIcon(AllIcons.Nodes.Plugin);
            append(skill.getName());

            if (skill.isInstalled()) {
                append("  Installed", new SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD,
                        new Color(66, 160, 83)));
            }

            if (skill.getDescription() != null && !skill.getDescription().isEmpty()) {
                String desc = skill.getDescription().replace("\n", " ");
                if (desc.length() > 60)
                    desc = desc.substring(0, 60) + "...";
                append(" - " + desc, SimpleTextAttributes.GRAYED_ATTRIBUTES);
            }

            setBorder(JBUI.Borders.empty(4, 8));
        }
    }
}
