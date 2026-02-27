package com.github.mostbean.codingswitch.ui.panel;

import com.github.mostbean.codingswitch.model.CliType;
import com.github.mostbean.codingswitch.model.McpServer;
import com.github.mostbean.codingswitch.service.McpService;
import com.github.mostbean.codingswitch.ui.dialog.McpServerDialog;
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
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * MCP 服务器管理面板。
 * 表格展示每个 MCP 对各 CLI 的独立开关（CheckBox 列）。
 */
public class McpPanel extends JPanel {

    private static final CliType[] CLI_TYPES = CliType.values();

    private final McpTableModel tableModel = new McpTableModel(this::markDirty);
    private final JBTable serverTable = new JBTable(tableModel);

    // 保存按钮相关的 state
    private boolean isDirty = false;
    private AnAction saveAction;

    public McpPanel() {
        setLayout(new BorderLayout());
        setBorder(JBUI.Borders.empty(8));

        add(createTablePanel(), BorderLayout.CENTER);

        McpService.getInstance().addChangeListener(this::refreshTable);
        refreshTable();
    }

    private JComponent createTablePanel() {
        serverTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        serverTable.getEmptyText().setText("暂无 MCP 服务器，点击 '+' 新增或 '从 CLI 导入'");
        serverTable.setRowHeight(JBUI.scale(28));

        // 列宽设置
        serverTable.getColumnModel().getColumn(0).setPreferredWidth(130); // 名称
        serverTable.getColumnModel().getColumn(1).setPreferredWidth(60); // 传输方式
        // CLI CheckBox 列
        for (int i = 0; i < CLI_TYPES.length; i++) {
            serverTable.getColumnModel().getColumn(2 + i).setPreferredWidth(70);
            serverTable.getColumnModel().getColumn(2 + i).setMaxWidth(90);
        }
        int detailCol = 2 + CLI_TYPES.length;
        serverTable.getColumnModel().getColumn(detailCol).setPreferredWidth(250); // 详情

        // 居中传输方式列
        DefaultTableCellRenderer centerRenderer = new DefaultTableCellRenderer();
        centerRenderer.setHorizontalAlignment(SwingConstants.CENTER);
        serverTable.getColumnModel().getColumn(1).setCellRenderer(centerRenderer);

        // 双击编辑
        serverTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int col = serverTable.columnAtPoint(e.getPoint());
                    // 双击非 CheckBox 列时打开编辑对话框
                    if (col < 2 || col >= 2 + CLI_TYPES.length) {
                        if (serverTable.getSelectedRow() != -1) {
                            onEdit();
                        }
                    }
                }
            }
        });

        saveAction = new AnAction("保存更改", "保存表格中的勾选状态变更", AllIcons.Actions.MenuSaveall) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                onSaveChanges();
            }

            @Override
            public void update(@NotNull AnActionEvent e) {
                e.getPresentation().setEnabled(isDirty);
            }
        };

        ToolbarDecorator decorator = ToolbarDecorator.createDecorator(serverTable)
                .setAddAction(button -> onAdd())
                .setEditAction(button -> onEdit())
                .setRemoveAction(button -> onDelete())
                .addExtraAction(saveAction)
                .addExtraAction(new AnAction("同步全部", "强制同步启用的服务器到所有 CLI 配置",
                        AllIcons.Actions.Refresh) {
                    @Override
                    public void actionPerformed(@NotNull AnActionEvent e) {
                        onSyncAll();
                    }
                })
                .addExtraAction(new AnAction("从 CLI 导入", "扫描 CLI 配置文件中已有的 MCP 服务器",
                        AllIcons.Actions.Download) {
                    @Override
                    public void actionPerformed(@NotNull AnActionEvent e) {
                        onImportFromCli();
                    }
                });

        return decorator.createPanel();
    }

    private void markDirty() {
        isDirty = true;
        // 触发 action 状态更新
        if (serverTable != null) {
            serverTable.repaint();
        }
    }

    // =====================================================================
    // 操作方法
    // =====================================================================

    private void onAdd() {
        McpServerDialog dialog = new McpServerDialog(null);
        if (dialog.showAndGet()) {
            if (dialog.isMultipleServers()) {
                for (McpServer s : dialog.getServers()) {
                    McpService.getInstance().addServer(s);
                }
                Messages.showInfoMessage(
                        "已导入 " + dialog.getServers().size() + " 个 MCP 服务器",
                        "导入完成");
            } else {
                McpService.getInstance().addServer(dialog.getServer());
            }
        }
    }

    private void onEdit() {
        McpServer selected = getSelectedServer();
        if (selected == null)
            return;
        McpServerDialog dialog = new McpServerDialog(selected);
        if (dialog.showAndGet()) {
            McpService.getInstance().updateServer(dialog.getServer());
        }
    }

    private void onDelete() {
        McpServer selected = getSelectedServer();
        if (selected == null)
            return;
        int result = Messages.showYesNoDialog(
                "确定删除 MCP 服务器 \"" + selected.getName() + "\" 吗？",
                "确认删除", Messages.getQuestionIcon());
        if (result == Messages.YES) {
            McpService.getInstance().removeServer(selected.getId());
        }
    }

    private void onSaveChanges() {
        if (!isDirty)
            return;
        List<McpServer> currentList = tableModel.getServers();
        for (McpServer s : currentList) {
            McpService.getInstance().updateServer(s);
        }
        isDirty = false;
        Messages.showInfoMessage("更改已保存并同步", "保存成功");
    }

    private void onSyncAll() {
        try {
            McpService.getInstance().syncToAllConfigs();
            Messages.showInfoMessage("MCP 服务器已成功同步到所有 CLI 配置", "同步完成");
        } catch (IOException ex) {
            Messages.showErrorDialog("同步失败: " + ex.getMessage(), "错误");
        }
    }

    private void onImportFromCli() {
        int count = McpService.getInstance().importFromCliConfigs();
        if (count > 0) {
            Messages.showInfoMessage(
                    "已从 CLI 配置文件中导入 " + count + " 个 MCP 服务器",
                    "导入完成");
        } else {
            Messages.showInfoMessage(
                    "未发现新的 MCP 服务器\n（已有的同名服务器会自动合并 CLI 开关）",
                    "导入完成");
        }
    }

    private McpServer getSelectedServer() {
        int row = serverTable.getSelectedRow();
        if (row >= 0) {
            return tableModel.getServerAt(row);
        }
        return null;
    }

    private void refreshTable() {
        // 深拷贝，防止表格直接修改了后台的 Service 数据模型
        List<McpServer> clones = new ArrayList<>();
        Gson gson = new Gson();
        for (McpServer s : McpService.getInstance().getServers()) {
            clones.add(gson.fromJson(gson.toJson(s), McpServer.class));
        }
        tableModel.setServers(clones);
        isDirty = false;
    }

    // =====================================================================
    // TableModel — 支持每 CLI 独立 CheckBox 列
    // =====================================================================

    private static class McpTableModel extends AbstractTableModel {

        // 列定义: 名称 | 传输方式 | Claude | Codex | Gemini | OpenCode | 详情
        private static final int COL_NAME = 0;
        private static final int COL_TRANSPORT = 1;
        private static final int COL_CLI_START = 2;
        private static final int COL_DETAIL = COL_CLI_START + CLI_TYPES.length;
        private static final int COLUMN_COUNT = COL_DETAIL + 1;

        private final Runnable dirtyCallback;
        private List<McpServer> data = new ArrayList<>();

        public McpTableModel(Runnable dirtyCallback) {
            this.dirtyCallback = dirtyCallback;
        }

        public void setServers(List<McpServer> servers) {
            this.data = new ArrayList<>(servers);
            fireTableDataChanged();
        }

        public List<McpServer> getServers() {
            return new ArrayList<>(data);
        }

        public McpServer getServerAt(int row) {
            return data.get(row);
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
                return "名称";
            if (column == COL_TRANSPORT)
                return "传输方式";
            if (column >= COL_CLI_START && column < COL_DETAIL) {
                return CLI_TYPES[column - COL_CLI_START].getDisplayName();
            }
            if (column == COL_DETAIL)
                return "详情";
            return "";
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            // CLI 开关列使用 Boolean 类型（JBTable 自动渲染为 CheckBox）
            if (columnIndex >= COL_CLI_START && columnIndex < COL_DETAIL) {
                return Boolean.class;
            }
            return String.class;
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return columnIndex >= COL_CLI_START && columnIndex < COL_DETAIL;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            McpServer s = data.get(rowIndex);
            if (columnIndex == COL_NAME) {
                return (s.isEnabled() ? "" : "⏸ ") + s.getName();
            }
            if (columnIndex == COL_TRANSPORT) {
                return s.getTransportType().name();
            }
            if (columnIndex >= COL_CLI_START && columnIndex < COL_DETAIL) {
                CliType cli = CLI_TYPES[columnIndex - COL_CLI_START];
                return s.isSyncedTo(cli);
            }
            if (columnIndex == COL_DETAIL) {
                if (s.getTransportType() == McpServer.TransportType.STDIO) {
                    return s.getCommand() + (s.getArgs() != null && s.getArgs().length > 0
                            ? " " + String.join(" ", s.getArgs())
                            : "");
                } else {
                    return s.getUrl() != null ? s.getUrl() : "";
                }
            }
            return "";
        }

        @Override
        public void setValueAt(Object aValue, int rowIndex, int columnIndex) {
            if (columnIndex >= COL_CLI_START && columnIndex < COL_DETAIL) {
                McpServer s = data.get(rowIndex);
                CliType cli = CLI_TYPES[columnIndex - COL_CLI_START];
                boolean enabled = Boolean.TRUE.equals(aValue);
                if (s.isSyncedTo(cli) != enabled) {
                    s.setSyncedTo(cli, enabled);
                    dirtyCallback.run();
                }
            }
        }
    }
}
