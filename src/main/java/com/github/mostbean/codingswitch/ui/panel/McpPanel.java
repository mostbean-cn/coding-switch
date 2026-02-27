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
 * 使用 JBTable 和 ToolbarDecorator 原生化组件。
 */
public class McpPanel extends JPanel {

    private final McpTableModel tableModel = new McpTableModel();
    private final JBTable serverTable = new JBTable(tableModel);

    public McpPanel() {
        setLayout(new BorderLayout());
        setBorder(JBUI.Borders.empty(8));

        add(createTablePanel(), BorderLayout.CENTER);

        McpService.getInstance().addChangeListener(this::refreshTable);
        refreshTable();
    }

    private JComponent createTablePanel() {
        serverTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        serverTable.getEmptyText().setText("暂无 MCP 服务器，点击 '+' 新增");
        serverTable.setRowHeight(JBUI.scale(24));

        serverTable.getColumnModel().getColumn(0).setPreferredWidth(120); // Name
        serverTable.getColumnModel().getColumn(1).setPreferredWidth(80); // Transport
        serverTable.getColumnModel().getColumn(2).setPreferredWidth(80); // Status
        serverTable.getColumnModel().getColumn(3).setPreferredWidth(150); // Targets
        serverTable.getColumnModel().getColumn(4).setPreferredWidth(250); // Details

        // 居中 Status 和 Transport
        DefaultTableCellRenderer centerRenderer = new DefaultTableCellRenderer();
        centerRenderer.setHorizontalAlignment(SwingConstants.CENTER);
        serverTable.getColumnModel().getColumn(1).setCellRenderer(centerRenderer);

        serverTable.getColumnModel().getColumn(2).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value,
                    boolean isSelected, boolean hasFocus,
                    int row, int column) {
                Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                if ("已启用".equals(value) && !isSelected) {
                    c.setForeground(new Color(66, 160, 83));
                    setFont(getFont().deriveFont(Font.BOLD));
                } else if ("已禁用".equals(value) && !isSelected) {
                    c.setForeground(UIManager.getColor("Label.disabledForeground"));
                }
                setHorizontalAlignment(SwingConstants.CENTER);
                return c;
            }
        });

        // 双击编辑
        serverTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && serverTable.getSelectedRow() != -1) {
                    onEdit();
                }
            }
        });

        ToolbarDecorator decorator = ToolbarDecorator.createDecorator(serverTable)
                .setAddAction(button -> onAdd())
                .setEditAction(button -> onEdit())
                .setRemoveAction(button -> onDelete())
                .addExtraAction(
                        new AnAction("启用/禁用", "切换选中服务器的启用状态", AllIcons.Actions.Suspend) {
                            @Override
                            public void actionPerformed(@NotNull AnActionEvent e) {
                                onToggle();
                            }

                            @Override
                            public void update(@NotNull AnActionEvent e) {
                                e.getPresentation().setEnabled(serverTable.getSelectedRow() != -1);
                            }
                        })
                .addExtraAction(new AnAction("同步全部", "同步已启用的服务器到所有 CLI 配置",
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

    private void onToggle() {
        McpServer selected = getSelectedServer();
        if (selected == null)
            return;
        McpService.getInstance().toggleServer(selected.getId());
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
                    "未发现新的 MCP 服务器\n（已有的同名服务器会自动跳过）",
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
        tableModel.setServers(McpService.getInstance().getServers());
    }

    // =====================================================================
    // TableModel
    // =====================================================================

    private static class McpTableModel extends AbstractTableModel {
        private final String[] COLUMNS = { "名称", "传输方式", "状态", "同步目标", "详情" };
        private List<McpServer> data = new ArrayList<>();

        public void setServers(List<McpServer> servers) {
            this.data = new ArrayList<>(servers);
            fireTableDataChanged();
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
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            McpServer s = data.get(rowIndex);
            switch (columnIndex) {
                case 0:
                    return s.getName();
                case 1:
                    return s.getTransportType().name();
                case 2:
                    return s.isEnabled() ? "已启用" : "已禁用";
                case 3:
                    List<String> targets = new ArrayList<>();
                    for (CliType cli : CliType.values()) {
                        if (s.isSyncedTo(cli))
                            targets.add(cli.name());
                    }
                    return targets.isEmpty() ? "无" : String.join(", ", targets);
                case 4:
                    if (s.getTransportType() == McpServer.TransportType.STDIO) {
                        return s.getCommand() + (s.getArgs() != null && s.getArgs().length > 0
                                ? " " + String.join(" ", s.getArgs())
                                : "");
                    } else {
                        return s.getUrl() != null ? s.getUrl() : "";
                    }
                default:
                    return "";
            }
        }
    }
}
