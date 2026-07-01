package com.github.mostbean.codingswitch.ui.dialog;

import com.github.mostbean.codingswitch.service.I18n;
import com.github.mostbean.codingswitch.service.PluginSettings;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * CLI 命令管理对话框
 */
public class CliCommandManagerDialog extends DialogWrapper {

    private final JTable commandTable;
    private final DefaultTableModel tableModel;

    public CliCommandManagerDialog(Component parent) {
        super(parent, true);
        setTitle(I18n.t("settings.dialog.cliCommand.configTitle"));

        // 初始化表格
        String[] columns = {
            I18n.t("settings.table.col.name"),
            I18n.t("settings.table.col.command")
        };
        tableModel = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };

        // 加载当前数据
        loadCommandsFromSettings();

        commandTable = new JTable(tableModel);
        commandTable.setRowHeight(JBUI.scale(24));
        commandTable.getColumnModel().getColumn(0).setPreferredWidth(JBUI.scale(120));
        commandTable.getColumnModel().getColumn(1).setPreferredWidth(JBUI.scale(300));
        commandTable.getTableHeader().setReorderingAllowed(false);
        commandTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel mainPanel = new JPanel(new BorderLayout(0, 8));
        mainPanel.setBorder(JBUI.Borders.empty(8));

        // 表格区域
        JScrollPane scrollPane = new JScrollPane(commandTable);
        scrollPane.setPreferredSize(new Dimension(JBUI.scale(500), JBUI.scale(200)));
        mainPanel.add(scrollPane, BorderLayout.CENTER);

        // 按钮区域
        JPanel buttonPanel = createButtonPanel();
        mainPanel.add(buttonPanel, BorderLayout.SOUTH);

        return mainPanel;
    }

    private JPanel createButtonPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));

        JButton addButton = new JButton(I18n.t("settings.button.addCliCommand"));
        addButton.setIcon(AllIcons.General.Add);
        addButton.addActionListener(e -> showAddOrEditDialog(-1));
        panel.add(addButton);

        JButton editButton = new JButton(I18n.t("settings.button.editCliCommand"));
        editButton.setIcon(AllIcons.Actions.Edit);
        editButton.addActionListener(e -> {
            int row = commandTable.getSelectedRow();
            if (row >= 0) {
                showAddOrEditDialog(row);
            }
        });
        panel.add(editButton);

        JButton removeButton = new JButton(I18n.t("settings.button.removeCliCommand"));
        removeButton.setIcon(AllIcons.General.Remove);
        removeButton.addActionListener(e -> {
            int row = commandTable.getSelectedRow();
            if (row >= 0) {
                tableModel.removeRow(row);
            }
        });
        panel.add(removeButton);

        return panel;
    }

    private void loadCommandsFromSettings() {
        tableModel.setRowCount(0);
        for (PluginSettings.CliQuickLaunchItem item : PluginSettings.getInstance().getCliQuickLaunchItems()) {
            tableModel.addRow(new Object[]{item.name, item.command});
        }
    }

    private void showAddOrEditDialog(int editRowIndex) {
        String defaultName = "";
        String defaultCmd = "";
        if (editRowIndex >= 0) {
            defaultName = String.valueOf(tableModel.getValueAt(editRowIndex, 0));
            defaultCmd = String.valueOf(tableModel.getValueAt(editRowIndex, 1));
        }

        CliCommandDialog dialog = new CliCommandDialog(commandTable, defaultName, defaultCmd);

        if (dialog.showAndGet()) {
            String name = dialog.getCliName();
            String command = dialog.getCliCommand();
            if (!name.isEmpty() && !command.isEmpty()) {
                if (editRowIndex >= 0) {
                    tableModel.setValueAt(name, editRowIndex, 0);
                    tableModel.setValueAt(command, editRowIndex, 1);
                } else {
                    tableModel.addRow(new Object[]{name, command});
                }
            }
        }
    }

    @Override
    protected void doOKAction() {
        // 保存到设置
        List<PluginSettings.CliQuickLaunchItem> items = new ArrayList<>();
        for (int i = 0; i < tableModel.getRowCount(); i++) {
            String name = String.valueOf(tableModel.getValueAt(i, 0)).trim();
            String command = String.valueOf(tableModel.getValueAt(i, 1)).trim();
            if (!name.isEmpty() && !command.isEmpty()) {
                items.add(new PluginSettings.CliQuickLaunchItem(name, command));
            }
        }

        PluginSettings settings = PluginSettings.getInstance();
        settings.setCliQuickLaunchItems(items);

        // 检查选中的命令是否还存在
        String selectedCmd = settings.getCliQuickLaunchSelectedCommand();
        boolean selectedExists = false;
        for (PluginSettings.CliQuickLaunchItem item : items) {
            if (item.command.equals(selectedCmd)) {
                selectedExists = true;
                break;
            }
        }
        if (items.isEmpty()) {
            settings.setCliQuickLaunchSelectedCommand("");
        } else if (selectedCmd == null || selectedCmd.isEmpty() || !selectedExists) {
            settings.setCliQuickLaunchSelectedCommand(items.get(0).command);
        }

        Messages.showInfoMessage(
            I18n.t("cliQuickLaunch.saved"),
            I18n.t("cliQuickLaunch.savedTitle")
        );

        super.doOKAction();
    }
}
