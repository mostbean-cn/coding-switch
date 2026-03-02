package com.github.mostbean.codingswitch.ui.panel;

import com.github.mostbean.codingswitch.model.CliType;
import com.github.mostbean.codingswitch.model.Skill;
import com.github.mostbean.codingswitch.service.I18n;
import com.github.mostbean.codingswitch.service.SkillService;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import com.google.gson.Gson;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Skills 管理面板。
 * 使用 JBTable 展示，包含每个 CLI 的独立开关列。
 */
public class SkillPanel extends JPanel {

    private static final CliType[] CLI_TYPES = CliType.values();

    private final SkillTableModel tableModel = new SkillTableModel(this::markDirty);
    private final JBTable skillTable = new JBTable(tableModel);

    // 保存按钮相关的 state
    private boolean isDirty = false;
    private AnAction saveAction;

    public SkillPanel() {
        setLayout(new BorderLayout());
        setBorder(JBUI.Borders.empty(8));

        add(createTablePanel(), BorderLayout.CENTER);

        SkillService.getInstance().addChangeListener(this::refreshTable);

        // 第一次进入自动扫描本地
        SkillService.getInstance().syncLocalSkills(SkillService.getInstance().scanLocalSkills());
        refreshTable();
    }

    private JComponent createTablePanel() {
        skillTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        skillTable.getEmptyText().setText(I18n.t("skill.table.empty"));
        skillTable.setRowHeight(JBUI.scale(28));

        // 列宽设置
        skillTable.getColumnModel().getColumn(0).setPreferredWidth(140); // 名称
        skillTable.getColumnModel().getColumn(1).setPreferredWidth(60); // 状态
        // CLI CheckBox 列
        for (int i = 0; i < CLI_TYPES.length; i++) {
            skillTable.getColumnModel().getColumn(2 + i).setPreferredWidth(70);
            skillTable.getColumnModel().getColumn(2 + i).setMaxWidth(90);
        }
        int descCol = 2 + CLI_TYPES.length;
        skillTable.getColumnModel().getColumn(descCol).setPreferredWidth(250); // 描述

        // 状态列居中 + 颜色
        skillTable.getColumnModel().getColumn(1).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value,
                    boolean isSelected, boolean hasFocus,
                    int row, int column) {
                Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                if (I18n.t("skill.status.installed").equals(value) && !isSelected) {
                    c.setForeground(new Color(66, 160, 83));
                    setFont(getFont().deriveFont(Font.BOLD));
                } else if (!isSelected) {
                    c.setForeground(UIManager.getColor("Label.disabledForeground"));
                }
                setHorizontalAlignment(SwingConstants.CENTER);
                return c;
            }
        });

        saveAction = new AnAction(I18n.t("skill.action.save"), I18n.t("skill.action.save.tooltip"),
                AllIcons.Actions.MenuSaveall) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                onSaveChanges();
            }

            @Override
            public void update(@NotNull AnActionEvent e) {
                e.getPresentation().setEnabled(isDirty);
            }
        };

        ToolbarDecorator decorator = ToolbarDecorator.createDecorator(skillTable)
                .setRemoveAction(button -> onUninstall())
                .setRemoveActionName(I18n.t("skill.action.uninstall"))
                .addExtraAction(saveAction)
                .addExtraAction(new AnAction(I18n.t("skill.action.scanLocal"), I18n.t("skill.action.scanLocal.tooltip"),
                        AllIcons.Actions.Refresh) {
                    @Override
                    public void actionPerformed(@NotNull AnActionEvent e) {
                        onScanLocal();
                    }
                })
                .addExtraAction(
                        new AnAction(I18n.t("skill.action.addRepo"), I18n.t("skill.action.addRepo.tooltip"),
                                AllIcons.General.Add) {
                            @Override
                            public void actionPerformed(@NotNull AnActionEvent e) {
                                onAddRepo();
                            }
                        });

        decorator.disableAddAction();
        decorator.disableUpDownActions();

        return decorator.createPanel();
    }

    private void markDirty() {
        isDirty = true;
        // 触发 action 状态更新
        if (skillTable != null) {
            skillTable.repaint();
        }
    }

    // =====================================================================
    // 操作方法
    // =====================================================================

    private void onScanLocal() {
        List<Skill> localSkills = SkillService.getInstance().scanLocalSkills();
        tableModel.setSkills(localSkills);
        if (localSkills.isEmpty()) {
            Messages.showInfoMessage(
                    I18n.t("skill.dialog.scanEmpty"),
                    I18n.t("skill.dialog.scanTitle"));
        }
    }

    private void onUninstall() {
        int row = skillTable.getSelectedRow();
        if (row < 0)
            return;
        Skill selected = tableModel.getSkillAt(row);
        if (selected == null || !selected.isInstalled())
            return;
        int result = Messages.showYesNoDialog(
                I18n.t("skill.dialog.uninstallConfirm", selected.getName()),
                I18n.t("skill.dialog.uninstallTitle"), Messages.getWarningIcon());
        if (result == Messages.YES) {
            try {
                SkillService.getInstance().uninstallSkill(selected.getId());
                onScanLocal();
            } catch (IOException ex) {
                Messages.showErrorDialog(I18n.t("skill.dialog.uninstallFailed", ex.getMessage()),
                        I18n.t("provider.dialog.error"));
            }
        }
    }

    private void onAddRepo() {
        String url = Messages.showInputDialog(
                I18n.t("skill.dialog.addRepoPrompt"),
                I18n.t("skill.dialog.addRepoTitle"), Messages.getQuestionIcon());
        if (url != null && !url.isBlank()) {
            SkillService.getInstance().addCustomRepo(url.trim());
            Messages.showInfoMessage(I18n.t("skill.dialog.addRepoDone", url), I18n.t("skill.dialog.addRepoSuccess"));
        }
    }

    private void onSaveChanges() {
        if (!isDirty)
            return;
        List<Skill> currentList = tableModel.getSkills();
        for (Skill s : currentList) {
            SkillService.getInstance().updateSkill(s);
        }
        isDirty = false;
        Messages.showInfoMessage(I18n.t("skill.dialog.saveSuccess"), I18n.t("skill.dialog.saveTitle"));
    }

    private void refreshTable() {
        // 深拷贝防止本地表格直接修改了后台的 Service 数据模型
        List<Skill> clones = new ArrayList<>();
        Gson gson = new Gson();
        for (Skill s : SkillService.getInstance().getInstalledSkills()) {
            clones.add(gson.fromJson(gson.toJson(s), Skill.class));
        }
        tableModel.setSkills(clones);
        isDirty = false;
    }

    // =====================================================================
    // TableModel — 支持每 CLI 独立 CheckBox 列
    // =====================================================================

    private static class SkillTableModel extends AbstractTableModel {

        // 列定义: 名称 | 状态 | Claude | Codex | Gemini | OpenCode | 描述
        private static final int COL_NAME = 0;
        private static final int COL_STATUS = 1;
        private static final int COL_CLI_START = 2;
        private static final int COL_DESC = COL_CLI_START + CLI_TYPES.length;
        private static final int COLUMN_COUNT = COL_DESC + 1;

        private final Runnable dirtyCallback;
        private List<Skill> data = new ArrayList<>();

        public SkillTableModel(Runnable dirtyCallback) {
            this.dirtyCallback = dirtyCallback;
        }

        public void setSkills(List<Skill> skills) {
            this.data = new ArrayList<>(skills);
            fireTableDataChanged();
        }

        public List<Skill> getSkills() {
            return new ArrayList<>(data);
        }

        public Skill getSkillAt(int row) {
            if (row >= 0 && row < data.size())
                return data.get(row);
            return null;
        }

        @Override
        public int getRowCount() {
            return data.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMN_COUNT;
        }

        @Override
        public String getColumnName(int column) {
            if (column == COL_NAME)
                return I18n.t("skill.table.col.name");
            if (column == COL_STATUS)
                return I18n.t("skill.table.col.status");
            if (column >= COL_CLI_START && column < COL_DESC) {
                return CLI_TYPES[column - COL_CLI_START].getDisplayName();
            }
            if (column == COL_DESC)
                return I18n.t("skill.table.col.desc");
            return "";
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            if (columnIndex >= COL_CLI_START && columnIndex < COL_DESC) {
                return Boolean.class;
            }
            return String.class;
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            // 所有 CLI 列均可编辑
            return columnIndex >= COL_CLI_START && columnIndex < COL_DESC;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            Skill s = data.get(rowIndex);
            if (columnIndex == COL_NAME)
                return s.getName();
            if (columnIndex == COL_STATUS)
                return s.isInstalled() ? I18n.t("skill.status.installed") : I18n.t("skill.status.notInstalled");
            if (columnIndex >= COL_CLI_START && columnIndex < COL_DESC) {
                CliType cli = CLI_TYPES[columnIndex - COL_CLI_START];
                return s.isSyncedTo(cli);
            }
            if (columnIndex == COL_DESC) {
                String desc = s.getDescription();
                if (desc == null || desc.isEmpty())
                    return "";
                desc = desc.replace("\n", " ");
                return desc.length() > 80 ? desc.substring(0, 80) + "..." : desc;
            }
            return "";
        }

        @Override
        public void setValueAt(Object aValue, int rowIndex, int columnIndex) {
            if (columnIndex >= COL_CLI_START && columnIndex < COL_DESC) {
                Skill s = data.get(rowIndex);
                CliType cli = CLI_TYPES[columnIndex - COL_CLI_START];
                boolean enabled = Boolean.TRUE.equals(aValue);
                if (s.isSyncedTo(cli) != enabled) {
                    s.setSyncedTo(cli, enabled);
                    dirtyCallback.run();
                    fireTableCellUpdated(rowIndex, columnIndex);
                }
            }
        }
    }
}
