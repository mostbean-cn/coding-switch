package com.github.mostbean.codingswitch.ui.panel;

import com.github.mostbean.codingswitch.model.CliType;
import com.github.mostbean.codingswitch.model.Provider;
import com.github.mostbean.codingswitch.service.ProviderService;
import com.github.mostbean.codingswitch.ui.dialog.ProviderDialog;
import com.google.gson.JsonObject;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.components.JBLabel;
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
 * Provider 管理面板。
 * 使用 JBTable 与 ToolbarDecorator 实现原生化的 UI。
 */
public class ProviderPanel extends JPanel {

    private final ProviderTableModel tableModel = new ProviderTableModel();
    private final JBTable providerTable = new JBTable(tableModel);
    private final JComboBox<CliType> filterCombo = new JComboBox<>();

    public ProviderPanel() {
        setLayout(new BorderLayout());
        setBorder(JBUI.Borders.empty(8));

        add(createToolbar(), BorderLayout.NORTH);
        add(createTablePanel(), BorderLayout.CENTER);

        // 监听数据变化
        ProviderService.getInstance().addChangeListener(this::refreshTable);
        refreshTable();
    }

    private JPanel createToolbar() {
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        toolbar.add(new JBLabel("筛选 CLI: "));

        filterCombo.addItem(null); // "All" 选项
        for (CliType cli : CliType.values()) {
            filterCombo.addItem(cli);
        }
        filterCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value,
                    int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                setText(value == null ? "全部" : ((CliType) value).getDisplayName());
                return this;
            }
        });
        filterCombo.addActionListener(e -> refreshTable());
        toolbar.add(filterCombo);

        return toolbar;
    }

    private JComponent createTablePanel() {
        providerTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        providerTable.getEmptyText().setText("暂无配置，点击 '+' 新增");
        providerTable.setRowHeight(JBUI.scale(24));

        // 列宽调整
        providerTable.getColumnModel().getColumn(0).setPreferredWidth(150); // Name
        providerTable.getColumnModel().getColumn(1).setPreferredWidth(100); // CLI Types
        providerTable.getColumnModel().getColumn(2).setPreferredWidth(80); // Status
        providerTable.getColumnModel().getColumn(3).setPreferredWidth(250); // Model

        // Status 列自定义渲染器：绿色高亮 Active
        providerTable.getColumnModel().getColumn(2).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value,
                    boolean isSelected, boolean hasFocus,
                    int row, int column) {
                Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                if ("已激活".equals(value) && !isSelected) {
                    c.setForeground(new Color(66, 160, 83)); // 柔和的绿色
                    setFont(getFont().deriveFont(Font.BOLD));
                } else if (!isSelected) {
                    c.setForeground(table.getForeground());
                }
                setHorizontalAlignment(SwingConstants.CENTER);
                return c;
            }
        });

        // 双击编辑
        providerTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && providerTable.getSelectedRow() != -1) {
                    onEdit();
                }
            }
        });

        // 使用 ToolbarDecorator 包装表格
        ToolbarDecorator decorator = ToolbarDecorator.createDecorator(providerTable)
                .setAddAction(button -> onAdd())
                .setEditAction(button -> onEdit())
                .setRemoveAction(button -> onDelete())
                .addExtraAction(new AnAction("复制", "复制选中的配置", AllIcons.Actions.Copy) {
                    @Override
                    public void actionPerformed(@NotNull AnActionEvent e) {
                        onDuplicate();
                    }

                    @Override
                    public void update(@NotNull AnActionEvent e) {
                        e.getPresentation().setEnabled(providerTable.getSelectedRow() != -1);
                    }
                })
                .addExtraAction(new AnAction("激活", "激活选中的配置并同步到 CLI",
                        AllIcons.Actions.Execute) {
                    @Override
                    public void actionPerformed(@NotNull AnActionEvent e) {
                        onActivate();
                    }

                    @Override
                    public void update(@NotNull AnActionEvent e) {
                        e.getPresentation().setEnabled(providerTable.getSelectedRow() != -1);
                    }
                });

        return decorator.createPanel();
    }

    // =====================================================================
    // 操作方法
    // =====================================================================

    private void onAdd() {
        ProviderDialog dialog = new ProviderDialog(null);
        if (dialog.showAndGet()) {
            ProviderService.getInstance().addProvider(dialog.getProvider());
        }
    }

    private void onEdit() {
        Provider selected = getSelectedProvider();
        if (selected == null)
            return;
        ProviderDialog dialog = new ProviderDialog(selected);
        if (dialog.showAndGet()) {
            ProviderService.getInstance().updateProvider(dialog.getProvider());
        }
    }

    private void onDelete() {
        Provider selected = getSelectedProvider();
        if (selected == null)
            return;
        int result = Messages.showYesNoDialog(
                "确定删除配置 \"" + selected.getName() + "\" 吗？",
                "确认删除", Messages.getQuestionIcon());
        if (result == Messages.YES) {
            ProviderService.getInstance().removeProvider(selected.getId());
        }
    }

    private void onDuplicate() {
        Provider selected = getSelectedProvider();
        if (selected == null)
            return;
        ProviderService.getInstance().duplicateProvider(selected.getId());
    }

    private void onActivate() {
        Provider selected = getSelectedProvider();
        if (selected == null)
            return;
        try {
            ProviderService.getInstance().activateProvider(selected.getId());
            Messages.showInfoMessage(
                    "配置 \"" + selected.getName() + "\" 已激活\n" +
                            "已同步到 " + selected.getCliType().getDisplayName() + "",
                    "激活成功");
        } catch (IOException ex) {
            Messages.showErrorDialog("激活失败: " + ex.getMessage(), "错误");
        }
    }

    private Provider getSelectedProvider() {
        int row = providerTable.getSelectedRow();
        if (row >= 0) {
            return tableModel.getProviderAt(row);
        }
        return null;
    }

    private void refreshTable() {
        CliType filter = (CliType) filterCombo.getSelectedItem();
        List<Provider> providers = filter == null
                ? ProviderService.getInstance().getProviders()
                : ProviderService.getInstance().getProvidersByType(filter);
        tableModel.setProviders(providers);
    }

    // =====================================================================
    // TableModel
    // =====================================================================

    private static class ProviderTableModel extends AbstractTableModel {
        private final String[] COLUMNS = { "名称", "CLI", "状态", "模型" };
        private List<Provider> data = new ArrayList<>();

        public void setProviders(List<Provider> providers) {
            this.data = new ArrayList<>(providers);
            fireTableDataChanged();
        }

        public Provider getProviderAt(int row) {
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
            Provider p = data.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> p.getName();
                case 1 -> p.getCliType().getDisplayName();
                case 2 -> p.isActive() ? "已激活" : "-";
                // 提取主要模型名称用于显示
                case 3 -> extractModelName(p.getCliType(), p.getSettingsConfig());
                default -> "";
            };
        }

        private String extractModelName(CliType cliType, JsonObject config) {
            if (config == null)
                return "N/A";
            try {
                return switch (cliType) {
                    case CLAUDE -> config.has("env") && config.getAsJsonObject("env").has("ANTHROPIC_MODEL")
                            ? config.getAsJsonObject("env").get("ANTHROPIC_MODEL").getAsString()
                            : "N/A";
                    case CODEX -> config.has("config") && config.get("config").getAsString().contains("model =")
                            ? extractTomlModel(config.get("config").getAsString())
                            : "N/A";
                    case GEMINI -> config.has("env") && config.getAsJsonObject("env").has("GEMINI_MODEL")
                            ? config.getAsJsonObject("env").get("GEMINI_MODEL").getAsString()
                            : "N/A";
                    case OPENCODE -> config.has("models") && !config.getAsJsonObject("models").keySet().isEmpty()
                            ? config.getAsJsonObject("models").keySet().iterator().next()
                            : "N/A";
                };
            } catch (Exception e) {
                return "Parsing Error";
            }
        }

        private String extractTomlModel(String toml) {
            for (String line : toml.split("\n")) {
                if (line.trim().startsWith("model =")) {
                    return line.substring(line.indexOf('=') + 1).trim().replace("\"", "");
                }
            }
            return "N/A";
        }
    }
}
