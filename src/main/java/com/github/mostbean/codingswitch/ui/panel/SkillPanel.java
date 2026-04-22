package com.github.mostbean.codingswitch.ui.panel;

import com.github.mostbean.codingswitch.model.CliType;
import com.github.mostbean.codingswitch.model.Skill;
import com.github.mostbean.codingswitch.service.I18n;
import com.github.mostbean.codingswitch.service.SkillService;
import com.github.mostbean.codingswitch.ui.dialog.SkillDiscoveryDialog;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.intellij.icons.AllIcons;
import com.intellij.ide.ActivityTracker;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
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
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private volatile boolean hasInstalledSelection = false;
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
        skillTable.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) {
                return;
            }
            updateSelectedSkillState();
        });
        skillTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e) || e.getClickCount() != 1) {
                    return;
                }
                int viewRow = skillTable.rowAtPoint(e.getPoint());
                int viewColumn = skillTable.columnAtPoint(e.getPoint());
                if (viewRow < 0 || viewColumn < 0) {
                    return;
                }
                int modelRow = skillTable.convertRowIndexToModel(viewRow);
                int modelColumn = skillTable.convertColumnIndexToModel(viewColumn);
                if (modelColumn == SkillTableModel.COL_NAME && tableModel.toggleRepositoryExpanded(modelRow)) {
                    updateSelectedSkillState();
                }
            }
        });

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

        skillTable.getColumnModel().getColumn(0).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value,
                    boolean isSelected, boolean hasFocus,
                    int row, int column) {
                Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                int modelRow = table.convertRowIndexToModel(row);
                SkillTableModel.SkillRow skillRow = tableModel.getRowAt(modelRow);
                if (skillRow != null && skillRow.isRepositoryPackage()) {
                    setFont(getFont().deriveFont(Font.BOLD));
                } else {
                    setFont(getFont().deriveFont(Font.PLAIN));
                }
                if (!isSelected && skillRow != null && skillRow.isRepositoryChild() && !skillRow.isOwned()) {
                    c.setForeground(UIManager.getColor("Label.disabledForeground"));
                }
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
                    public @NotNull ActionUpdateThread getActionUpdateThread() {
                        return ActionUpdateThread.BGT;
                    }

                    @Override
                    public void actionPerformed(@NotNull AnActionEvent e) {
                        onUpdateSelected();
                    }

                    @Override
                    public void update(@NotNull AnActionEvent e) {
                        e.getPresentation().setEnabled(hasInstalledSelection);
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
        SkillService.SkillBridgeSyncResult syncResult = service.syncSkillBridgesToCli();
        refreshTable();

        if (syncResult.failed() > 0) {
            Messages.showWarningDialog(
                    I18n.t("skill.dialog.scanWithBridgeSyncFailed", localSkills.size(), syncResult.failed(),
                            syncResult.detail()),
                    I18n.t("skill.dialog.scanTitle"));
            return;
        }

        if (localSkills.isEmpty()) {
            Messages.showInfoMessage(
                    I18n.t("skill.dialog.scanEmpty"),
                    I18n.t("skill.dialog.scanTitle"));
            return;
        }

        Messages.showInfoMessage(
                I18n.t("skill.dialog.scanDone", localSkills.size()),
                I18n.t("skill.dialog.scanTitle"));
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
        Skill selected = getSelectedSkill();
        if (selected == null) {
            return;
        }
        if (!selected.isRepositoryPackage() && !SkillService.getInstance().isGitAvailable()) {
            Messages.showWarningDialog(I18n.t("skill.dialog.gitRequired"), I18n.t("provider.dialog.error"));
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
        SkillTableModel.SkillRow selectedRow = getSelectedSkillRow();
        if (selectedRow != null && selectedRow.isRepositoryChild()) {
            Messages.showInfoMessage(
                    I18n.t("skill.dialog.removeChildNotSupported"),
                    I18n.t("skill.dialog.removeTitle"));
            return;
        }
        Skill selected = selectedRow == null ? null : selectedRow.skill();
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
            if (!hasCliSyncChanged(current, before)) {
                continue;
            }
            for (CliType cliType : CLI_TYPES) {
                if (isSkillOrChildSyncedTo(current, cliType)) {
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
        updateSelectedSkillState();
    }

    private Skill getSelectedSkill() {
        SkillTableModel.SkillRow row = getSelectedSkillRow();
        return row == null ? null : row.skill();
    }

    private SkillTableModel.SkillRow getSelectedSkillRow() {
        int viewRow = skillTable.getSelectedRow();
        if (viewRow < 0) {
            return null;
        }
        int modelRow = skillTable.convertRowIndexToModel(viewRow);
        return tableModel.getRowAt(modelRow);
    }

    private void updateSelectedSkillState() {
        SkillTableModel.SkillRow selected = getSelectedSkillRow();
        hasInstalledSelection = selected != null && selected.isRepositoryPackage() && selected.isInstalled();
        ActivityTracker.getInstance().inc();
    }

    private static boolean hasCliSyncChanged(Skill current, Skill before) {
        for (CliType cliType : CLI_TYPES) {
            if (current.isSyncedTo(cliType) != (before != null && before.isSyncedTo(cliType))) {
                return true;
            }
        }
        if (current.isRepositoryPackage() && current.getChildren() != null) {
            for (Skill.SkillChild child : current.getChildren()) {
                Skill.SkillChild beforeChild = findChildByName(before, child == null ? null : child.getName());
                for (CliType cliType : CLI_TYPES) {
                    boolean now = child != null && child.isSyncedTo(cliType);
                    boolean old = beforeChild != null && beforeChild.isSyncedTo(cliType);
                    if (now != old) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean isSkillOrChildSyncedTo(Skill skill, CliType cliType) {
        if (skill.isSyncedTo(cliType)) {
            return true;
        }
        if (!skill.isRepositoryPackage() || skill.getChildren() == null) {
            return false;
        }
        for (Skill.SkillChild child : skill.getChildren()) {
            if (child != null && child.isSyncedTo(cliType)) {
                return true;
            }
        }
        return false;
    }

    private static Skill.SkillChild findChildByName(Skill skill, String childName) {
        if (skill == null || skill.getChildren() == null || childName == null) {
            return null;
        }
        for (Skill.SkillChild child : skill.getChildren()) {
            if (child != null && childName.equalsIgnoreCase(String.valueOf(child.getName()))) {
                return child;
            }
        }
        return null;
    }

    // =====================================================================
    // TableModel — 支持每 CLI 独立 CheckBox 列
    // =====================================================================

    private static class SkillTableModel extends AbstractTableModel {

        static class SkillRow {
            private final Skill skill;
            private final Skill.SkillChild child;
            private final boolean expanded;

            SkillRow(Skill skill, Skill.SkillChild child, boolean expanded) {
                this.skill = skill;
                this.child = child;
                this.expanded = expanded;
            }

            Skill skill() {
                return skill;
            }

            boolean isRepositoryPackage() {
                return skill != null && child == null && skill.isRepositoryPackage();
            }

            boolean isExpanded() {
                return expanded;
            }

            boolean isRepositoryChild() {
                return child != null;
            }

            boolean isInstalled() {
                return child == null ? skill != null && skill.isInstalled() : child.isInstalled();
            }

            boolean isOwned() {
                return child == null || child.isOwned();
            }

            String displayName() {
                if (child == null) {
                    if (skill == null) {
                        return "";
                    }
                    if (skill.isRepositoryPackage()) {
                        return (expanded ? "[-] " : "[+] ") + skill.getName();
                    }
                    return skill.getName();
                }
                return "  - " + child.getName();
            }

            boolean isSyncedTo(CliType cliType) {
                if (child == null) {
                    return skill != null && skill.isSyncedTo(cliType);
                }
                return child.isOwned() && child.isInstalled()
                        && ((skill != null && skill.isSyncedTo(cliType)) || child.isSyncedTo(cliType));
            }

            void setSyncedTo(CliType cliType, boolean enabled) {
                if (child == null) {
                    skill.setSyncedTo(cliType, enabled);
                } else {
                    child.setSyncedTo(cliType, enabled);
                }
            }

            boolean canEditCli(CliType cliType) {
                if (!isInstalled()) {
                    return false;
                }
                if (child == null) {
                    return true;
                }
                return child.isOwned() && (skill == null || !skill.isSyncedTo(cliType));
            }
        }

        // 列定义: 名称 | 状态 | Claude | Codex | Gemini | OpenCode | 描述
        private static final int COL_NAME = 0;
        private static final int COL_STATUS = 1;
        private static final int COL_CLI_START = 2;
        private static final int COL_DESC = COL_CLI_START + CLI_TYPES.length;
        private static final int COLUMN_COUNT = COL_DESC + 1;

        private final Runnable dirtyCallback;
        private List<Skill> data = new ArrayList<>();
        private List<SkillRow> rows = new ArrayList<>();
        private final Set<String> expandedRepositoryIds = new HashSet<>();

        public SkillTableModel(Runnable dirtyCallback) {
            this.dirtyCallback = dirtyCallback;
        }

        public void setSkills(List<Skill> skills) {
            this.data = new ArrayList<>(skills);
            rebuildRows();
            fireTableDataChanged();
        }

        public List<Skill> getSkills() {
            return new ArrayList<>(data);
        }

        public Skill getSkillAt(int row) {
            SkillRow skillRow = getRowAt(row);
            if (skillRow != null) {
                return skillRow.skill();
            }
            return null;
        }

        public SkillRow getRowAt(int row) {
            if (row >= 0 && row < rows.size()) {
                return rows.get(row);
            }
            return null;
        }

        private void rebuildRows() {
            pruneExpandedRepositoryIds();
            List<SkillRow> rebuilt = new ArrayList<>();
            for (Skill skill : data) {
                boolean expanded = isRepositoryExpanded(skill);
                rebuilt.add(new SkillRow(skill, null, expanded));
                if (skill == null || !skill.isRepositoryPackage() || skill.getChildren() == null) {
                    continue;
                }
                if (!expanded) {
                    continue;
                }
                for (Skill.SkillChild child : skill.getChildren()) {
                    if (child != null) {
                        rebuilt.add(new SkillRow(skill, child, false));
                    }
                }
            }
            this.rows = rebuilt;
        }

        public boolean toggleRepositoryExpanded(int row) {
            SkillRow skillRow = getRowAt(row);
            if (skillRow == null || !skillRow.isRepositoryPackage() || skillRow.skill() == null) {
                return false;
            }
            String key = repositoryExpansionKey(skillRow.skill());
            if (expandedRepositoryIds.contains(key)) {
                expandedRepositoryIds.remove(key);
            } else {
                expandedRepositoryIds.add(key);
            }
            rebuildRows();
            fireTableDataChanged();
            return true;
        }

        private boolean isRepositoryExpanded(Skill skill) {
            return skill != null && expandedRepositoryIds.contains(repositoryExpansionKey(skill));
        }

        private void pruneExpandedRepositoryIds() {
            Set<String> existingIds = new HashSet<>();
            for (Skill skill : data) {
                if (skill != null && skill.isRepositoryPackage()) {
                    existingIds.add(repositoryExpansionKey(skill));
                }
            }
            expandedRepositoryIds.retainAll(existingIds);
        }

        private static String repositoryExpansionKey(Skill skill) {
            if (skill.getId() != null && !skill.getId().isBlank()) {
                return skill.getId();
            }
            return String.valueOf(skill.getRepository()) + "#" + String.valueOf(skill.getBranch());
        }

        @Override
        public int getRowCount() {
            return rows.size();
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
            if (columnIndex < COL_CLI_START || columnIndex >= COL_DESC) {
                return false;
            }
            SkillRow row = getRowAt(rowIndex);
            return row != null && row.canEditCli(CLI_TYPES[columnIndex - COL_CLI_START]);
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            SkillRow row = rows.get(rowIndex);
            Skill s = row.skill();
            if (columnIndex == COL_NAME)
                return row.displayName();
            if (columnIndex == COL_STATUS)
                return row.isInstalled() ? I18n.t("skill.status.installed") : I18n.t("skill.status.notInstalled");
            if (columnIndex >= COL_CLI_START && columnIndex < COL_DESC) {
                CliType cli = CLI_TYPES[columnIndex - COL_CLI_START];
                return row.isSyncedTo(cli);
            }
            if (columnIndex == COL_DESC) {
                if (row.isRepositoryChild()) {
                    String desc = row.child.getRelativePath();
                    if (desc == null || desc.isBlank()) {
                        desc = row.child.getName();
                    }
                    if (desc == null || desc.isBlank()) {
                        desc = "";
                    }
                    if (!row.isOwned()) {
                        String unowned = I18n.t("skill.table.repositoryChildUnowned");
                        desc = desc.isBlank() ? unowned : desc + " - " + unowned;
                    }
                    return desc.length() > 80 ? desc.substring(0, 80) + "..." : desc;
                }
                String desc = s.getDescription();
                if (s.isRepositoryPackage()) {
                    int childCount = s.getChildren() == null ? 0 : s.getChildren().size();
                    String prefix = I18n.t("skill.table.repositoryPackageDesc", childCount);
                    desc = desc == null || desc.isBlank() ? prefix : prefix + " · " + desc;
                }
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
                SkillRow row = getRowAt(rowIndex);
                if (row == null) {
                    return;
                }
                CliType cli = CLI_TYPES[columnIndex - COL_CLI_START];
                boolean enabled = Boolean.TRUE.equals(aValue);
                if (row.isSyncedTo(cli) != enabled) {
                    row.setSyncedTo(cli, enabled);
                    dirtyCallback.run();
                    fireTableDataChanged();
                }
            }
        }
    }
}
