package com.github.mostbean.codingswitch.ui.panel;

import com.github.mostbean.codingswitch.model.CliType;
import com.github.mostbean.codingswitch.model.Provider;
import com.github.mostbean.codingswitch.service.CodexActivationResult;
import com.github.mostbean.codingswitch.service.ClaudeTemporaryLaunchService;
import com.github.mostbean.codingswitch.service.GeminiActivationResult;
import com.github.mostbean.codingswitch.service.I18n;
import com.github.mostbean.codingswitch.service.PluginSettings;
import com.github.mostbean.codingswitch.service.ProviderService;
import com.github.mostbean.codingswitch.ui.action.TerminalSessionService;
import com.github.mostbean.codingswitch.ui.dialog.ProviderDialog;
import com.google.gson.JsonObject;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
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

    private final Project project;
    private final ProviderTableModel tableModel = new ProviderTableModel();
    private final JBTable providerTable = new JBTable(tableModel);
    private final JComboBox<CliType> filterCombo = new JComboBox<>();

    public ProviderPanel(Project project) {
        this.project = project;
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
        toolbar.add(new JBLabel(I18n.t("provider.filter.label")));

        filterCombo.addItem(null); // "All" 选项
        for (CliType cli : PluginSettings.getInstance().getVisibleManagedCliTypes()) {
            filterCombo.addItem(cli);
        }
        filterCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value,
                    int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                setText(value == null ? I18n.t("provider.filter.all") : ((CliType) value).getDisplayName());
                return this;
            }
        });
        CliType savedCli = PluginSettings.getInstance().getProviderFilterCli();
        filterCombo.setSelectedItem(isFilterCliAvailable(savedCli) ? savedCli : null);
        filterCombo.addActionListener(e -> {
            PluginSettings.getInstance().setProviderFilterCli((CliType) filterCombo.getSelectedItem());
            refreshTable();
        });
        toolbar.add(filterCombo);

        return toolbar;
    }

    private boolean isFilterCliAvailable(CliType cliType) {
        if (cliType == null) {
            return true;
        }
        for (int i = 0; i < filterCombo.getItemCount(); i++) {
            if (filterCombo.getItemAt(i) == cliType) {
                return true;
            }
        }
        return false;
    }

    private JComponent createTablePanel() {
        providerTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        providerTable.getEmptyText().setText(I18n.t("provider.table.empty"));
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
                if (!isSelected) {
                    if (I18n.t("provider.status.active").equals(value)) {
                        c.setForeground(new Color(66, 160, 83)); // 柔和的绿色
                        setFont(getFont().deriveFont(Font.BOLD));
                    } else if (I18n.t("provider.status.pendingActivation").equals(value)) {
                        c.setForeground(new Color(245, 158, 11)); // 柔和橙色
                        setFont(getFont().deriveFont(Font.BOLD));
                    } else {
                        c.setForeground(table.getForeground());
                        setFont(table.getFont());
                    }
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
                .addExtraAction(new AnAction(I18n.t("provider.action.duplicate"),
                        I18n.t("provider.action.duplicate.tooltip"), AllIcons.Actions.Copy) {
                    @Override
                    public void actionPerformed(@NotNull AnActionEvent e) {
                        onDuplicate();
                    }

                    @Override
                    public void update(@NotNull AnActionEvent e) {
                        e.getPresentation().setEnabled(providerTable.getSelectedRow() != -1);
                    }
                })
                .addExtraAction(new AnAction(I18n.t("provider.action.refresh"),
                        I18n.t("provider.action.refresh.tooltip"), AllIcons.Actions.Refresh) {
                    @Override
                    public void actionPerformed(@NotNull AnActionEvent e) {
                        onRefreshExternalChanges();
                    }
                })
                .addExtraAction(
                        new AnAction(I18n.t("provider.action.activate"), I18n.t("provider.action.activate.tooltip"),
                                AllIcons.Actions.Execute) {
                            @Override
                            public void actionPerformed(@NotNull AnActionEvent e) {
                                onActivate();
                            }

                            @Override
                            public void update(@NotNull AnActionEvent e) {
                                e.getPresentation().setEnabled(providerTable.getSelectedRow() != -1);
                            }
                        })
                .addExtraAction(
                        new AnAction(I18n.t("provider.action.tempLaunch"),
                                I18n.t("provider.action.tempLaunch.tooltip"),
                                AllIcons.Actions.Lightning) {
                            @Override
                            public void actionPerformed(@NotNull AnActionEvent e) {
                                onTemporaryLaunch();
                            }

                            @Override
                            public void update(@NotNull AnActionEvent e) {
                                CliType filter = (CliType) filterCombo.getSelectedItem();
                                boolean visible = filter == null || filter == CliType.CLAUDE;
                                Provider selected = getSelectedProvider();
                                boolean enabled = visible && selected != null && selected.getCliType() == CliType.CLAUDE;
                                e.getPresentation().setVisible(visible);
                                e.getPresentation().setEnabled(enabled);
                                e.getPresentation().setDescription(enabled
                                        ? I18n.t("provider.action.tempLaunch.tooltip")
                                        : I18n.t("provider.action.tempLaunch.disabledTooltip"));
                            }
                        });

        return decorator.createPanel();
    }

    // =====================================================================
    // 操作方法
    // =====================================================================

    private void onAdd() {
        CliType selectedCli = (CliType) filterCombo.getSelectedItem();
        ProviderDialog dialog = new ProviderDialog(null, selectedCli);
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
                I18n.t("provider.dialog.deleteConfirm", selected.getName()),
                I18n.t("provider.dialog.deleteTitle"), Messages.getQuestionIcon());
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

    private void onRefreshExternalChanges() {
        String selectedProviderId = null;
        Provider selected = getSelectedProvider();
        if (selected != null) {
            selectedProviderId = selected.getId();
        }
        refreshTable();
        restoreSelection(selectedProviderId);
    }

    private void onActivate() {
        Provider selected = getSelectedProvider();
        if (selected == null)
            return;

        try {
            ProviderService.getInstance().activateProvider(selected.getId());
            CodexActivationResult codexResult = ProviderService.getInstance().getLastCodexActivationResult();
            GeminiActivationResult geminiResult = ProviderService.getInstance().getLastGeminiActivationResult();
            Messages.showInfoMessage(
                    buildActivationMessage(selected, codexResult, geminiResult),
                    I18n.t("provider.dialog.activateTitle"));
        } catch (IOException ex) {
            Messages.showErrorDialog(I18n.t("provider.dialog.activateFailed", ex.getMessage()),
                    I18n.t("provider.dialog.error"));
        }
    }

    private void onTemporaryLaunch() {
        Provider selected = getSelectedProvider();
        if (selected == null) {
            return;
        }
        if (selected.getCliType() != CliType.CLAUDE) {
            Messages.showInfoMessage(
                    I18n.t("provider.dialog.tempLaunch.onlyClaude"),
                    I18n.t("provider.dialog.tempLaunch.title"));
            return;
        }
        if (project == null || project.isDisposed()) {
            Messages.showErrorDialog(
                    I18n.t("provider.dialog.tempLaunch.noProject"),
                    I18n.t("provider.dialog.error"));
            return;
        }

        try {
            ClaudeTemporaryLaunchService.LaunchRequest launchRequest =
                    ClaudeTemporaryLaunchService.buildLaunchRequest(selected);
            String workingDir = project.getBasePath() != null
                    ? project.getBasePath()
                    : System.getProperty("user.home");
            TerminalSessionService.executeCommand(
                    project,
                    workingDir,
                    "Claude: " + selected.getName(),
                    launchRequest.command(),
                    launchRequest.environment());
        } catch (RuntimeException ex) {
            Messages.showErrorDialog(
                    I18n.t("provider.dialog.tempLaunch.failed", safeMessage(ex)),
                    I18n.t("provider.dialog.error"));
        }
    }

    private String safeMessage(RuntimeException ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank()
                ? ex.getClass().getSimpleName()
                : message;
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
        List<Provider> providers;
        if (filter == null) {
            List<CliType> visibleCliTypes = PluginSettings.getInstance().getVisibleManagedCliTypes();
            providers = ProviderService.getInstance().getProviders().stream()
                    .filter(provider -> visibleCliTypes.contains(provider.getCliType()))
                    .toList();
        } else {
            providers = ProviderService.getInstance().getProvidersByType(filter);
        }
        tableModel.setProviders(providers);
    }

    private void restoreSelection(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            return;
        }
        for (int row = 0; row < tableModel.getRowCount(); row++) {
            Provider provider = tableModel.getProviderAt(row);
            if (providerId.equals(provider.getId())) {
                providerTable.getSelectionModel().setSelectionInterval(row, row);
                providerTable.scrollRectToVisible(providerTable.getCellRect(row, 0, true));
                return;
            }
        }
    }

    private String buildActivationMessage(Provider provider, CodexActivationResult codexResult,
            GeminiActivationResult geminiResult) {
        String base = I18n.t("provider.dialog.activateSuccess", provider.getName(),
                provider.getCliType().getDisplayName());

        // 处理 Codex 激活结果
        if (provider.getCliType() == CliType.CODEX
                && provider.getAuthMode() == Provider.AuthMode.OFFICIAL_LOGIN
                && codexResult != null) {
            String extra = switch (codexResult.getAuthSwitchState()) {
                case SNAPSHOT_RESTORED -> I18n.t("provider.dialog.codexAuth.restored");
                case LOGIN_REQUIRED -> I18n.t("provider.dialog.codexAuth.loginRequired");
                case SNAPSHOT_INVALID -> I18n.t("provider.dialog.codexAuth.snapshotInvalid");
                case NOT_APPLICABLE -> "";
            };
            if (!extra.isBlank()) {
                return base + "\n" + extra;
            }
        }

        // 处理 Gemini 激活结果
        if (provider.getCliType() == CliType.GEMINI
                && provider.getAuthMode() == Provider.AuthMode.OFFICIAL_LOGIN
                && geminiResult != null) {
            String extra = switch (geminiResult.getActivationState()) {
                case SNAPSHOT_RESTORED -> "✅ 已恢复之前的登录状态，无需重新登录";
                case LOGIN_REQUIRED -> "🔐 首次使用或无登录快照，请运行 CLI 完成官方登录";
                case SNAPSHOT_INVALID -> "⚠️ 历史登录已失效，请重新登录";
                case NOT_APPLICABLE -> "";
            };
            if (!extra.isBlank()) {
                return base + "\n" + extra;
            }
        }

        return base;
    }

    // =====================================================================
    // TableModel
    // =====================================================================

    private static class ProviderTableModel extends AbstractTableModel {
        private final String[] COLUMNS = { I18n.t("provider.table.col.name"), I18n.t("provider.table.col.cli"),
                I18n.t("provider.table.col.status"), I18n.t("provider.table.col.model") };
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
                case 2 -> {
                    if (p.isPendingActivation()) {
                        yield I18n.t("provider.status.pendingActivation");
                    }
                    yield p.isActive() ? I18n.t("provider.status.active") : "-";
                }
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
                            ? String.join(", ", config.getAsJsonObject("models").keySet())
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
