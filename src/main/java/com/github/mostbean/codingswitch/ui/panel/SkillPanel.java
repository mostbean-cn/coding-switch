package com.github.mostbean.codingswitch.ui.panel;

import com.github.mostbean.codingswitch.model.CliType;
import com.github.mostbean.codingswitch.model.Skill;
import com.github.mostbean.codingswitch.service.I18n;
import com.github.mostbean.codingswitch.service.SkillService;
import com.github.mostbean.codingswitch.ui.dialog.SkillDiscoveryDialog;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellEditor;
import java.awt.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
                .setAddAction(button -> onOpenDiscoveryDialog())
                .setAddActionName(I18n.t("skill.action.discoverFromRepos"))
                .setRemoveAction(button -> onRemoveSkill())
                .setRemoveActionName(I18n.t("skill.action.remove"))
                .addExtraAction(saveAction)
                .addExtraAction(new AnAction(I18n.t("skill.action.update"), I18n.t("skill.action.update.tooltip"),
                        AllIcons.Actions.Refresh) {
                    @Override
                    public void actionPerformed(@NotNull AnActionEvent e) {
                        onUpdateSelected();
                    }

                    @Override
                    public void update(@NotNull AnActionEvent e) {
                        Skill selected = getSelectedSkill();
                        e.getPresentation()
                                .setEnabled(SkillService.getInstance().isGitAvailable() && selected != null
                                        && selected.isInstalled());
                    }
                })
                .addExtraAction(new AnAction(I18n.t("skill.action.installZip"),
                        I18n.t("skill.action.installZip.tooltip"),
                        AllIcons.Actions.Download) {
                    @Override
                    public void actionPerformed(@NotNull AnActionEvent e) {
                        onImportZipPackage();
                    }
                })
                .addExtraAction(new AnAction(I18n.t("skill.action.scanLocal"), I18n.t("skill.action.scanLocal.tooltip"),
                        AllIcons.Actions.Find) {
                    @Override
                    public void actionPerformed(@NotNull AnActionEvent e) {
                        onScanLocal();
                    }
                });

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
        SkillService service = SkillService.getInstance();
        List<Skill> localSkills = service.scanLocalSkills();
        service.syncLocalSkills(localSkills);
        service.syncSkillBridgesToCli();
        refreshTable();
        if (localSkills.isEmpty()) {
            Messages.showInfoMessage(
                    I18n.t("skill.dialog.scanEmpty"),
                    I18n.t("skill.dialog.scanTitle"));
        }
    }

    private void onOpenDiscoveryDialog() {
        SkillDiscoveryDialog dialog = new SkillDiscoveryDialog();
        dialog.show();
        refreshTable();
    }

    private void onImportZipPackage() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(I18n.t("skill.dialog.zipChooserTitle"));
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        chooser.setMultiSelectionEnabled(false);
        chooser.setAcceptAllFileFilterUsed(false);
        chooser.setFileFilter(new FileNameExtensionFilter("ZIP (*.zip)", "zip"));

        int option = chooser.showOpenDialog(this);
        if (option != JFileChooser.APPROVE_OPTION || chooser.getSelectedFile() == null) {
            return;
        }

        Path zipFile = chooser.getSelectedFile().toPath();
        SkillService.OperationResult result = SkillService.getInstance().importSkillsFromLocalZip(zipFile);
        if (result.success()) {
            Messages.showInfoMessage(I18n.t("skill.dialog.importZipSuccess", result.message()),
                    I18n.t("skill.dialog.importZipTitle"));
        } else {
            Messages.showErrorDialog(I18n.t("skill.dialog.importZipFailed", result.message()),
                    I18n.t("provider.dialog.error"));
        }
        refreshTable();
    }

    private void onUpdateSelected() {
        if (!SkillService.getInstance().isGitAvailable()) {
            Messages.showWarningDialog(I18n.t("skill.dialog.gitRequired"), I18n.t("provider.dialog.error"));
            return;
        }
        Skill selected = getSelectedSkill();
        if (selected == null) {
            return;
        }
        SkillService.OperationResult result = SkillService.getInstance().updateInstalledSkill(selected.getId());
        if (result.success()) {
            Messages.showInfoMessage(I18n.t("skill.dialog.updateSuccess", selected.getName()),
                    I18n.t("skill.dialog.updateTitle"));
        } else {
            Messages.showErrorDialog(I18n.t("skill.dialog.updateFailed", result.message()),
                    I18n.t("provider.dialog.error"));
        }
    }

    private void onRemoveSkill() {
        Skill selected = getSelectedSkill();
        if (selected == null)
            return;
        int result = Messages.showYesNoDialog(
                I18n.t("skill.dialog.removeConfirm", selected.getName()),
                I18n.t("skill.dialog.removeTitle"), Messages.getWarningIcon());
        if (result == Messages.YES) {
            SkillService.getInstance().removeSkill(selected.getId());
            refreshTable();
        }
    }

    private void onSaveChanges() {
        if (skillTable.isEditing()) {
            TableCellEditor editor = skillTable.getCellEditor();
            if (editor != null) {
                editor.stopCellEditing();
            }
        }
        if (!isDirty)
            return;
        List<Skill> currentList = tableModel.getSkills();
        SkillService service = SkillService.getInstance();

        List<Skill> beforeList = service.getSkills();
        Map<String, Skill> beforeById = new HashMap<>();
        for (Skill skill : beforeList) {
            if (skill != null && skill.getId() != null) {
                beforeById.put(skill.getId(), skill);
            }
        }

        EnumSet<CliType> changedSelectedCliSet = EnumSet.noneOf(CliType.class);
        for (Skill current : currentList) {
            if (current == null || current.getId() == null || !current.isInstalled()) {
                continue;
            }
            Skill before = beforeById.get(current.getId());
            boolean changed = false;
            for (CliType cliType : CLI_TYPES) {
                boolean now = current.isSyncedTo(cliType);
                boolean old = before != null && before.isSyncedTo(cliType);
                if (now != old) {
                    changed = true;
                    break;
                }
            }
            if (!changed) {
                continue;
            }
            for (CliType cliType : CLI_TYPES) {
                if (current.isSyncedTo(cliType)) {
                    changedSelectedCliSet.add(cliType);
                }
            }
        }

        for (Skill s : currentList) {
            service.updateSkill(s);
        }
        SkillService.SkillBridgeSyncResult syncResult = service.syncSkillBridgesToCli();

        int selectedCliCount = changedSelectedCliSet.size();

        isDirty = false;
        if (syncResult.failed() > 0) {
            Messages.showWarningDialog(
                    I18n.t("skill.dialog.bridgeSyncPartial", selectedCliCount, syncResult.failed(),
                            syncResult.detail()),
                    I18n.t("skill.dialog.saveTitle"));
        } else {
            Messages.showInfoMessage(I18n.t("skill.dialog.bridgeSyncSuccess", selectedCliCount),
                    I18n.t("skill.dialog.saveTitle"));
        }
        refreshTable();
    }

    private void refreshTable() {
        // 深拷贝防止本地表格直接修改了后台的 Service 数据模型
        List<Skill> clones = new ArrayList<>();
        Gson gson = new GsonBuilder().enableComplexMapKeySerialization().create();
        for (Skill s : SkillService.getInstance().getSkills()) {
            clones.add(gson.fromJson(gson.toJson(s), Skill.class));
        }
        tableModel.setSkills(clones);
        isDirty = false;
    }

    private Skill getSelectedSkill() {
        int viewRow = skillTable.getSelectedRow();
        if (viewRow < 0) {
            return null;
        }
        int modelRow = skillTable.convertRowIndexToModel(viewRow);
        return tableModel.getSkillAt(modelRow);
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
