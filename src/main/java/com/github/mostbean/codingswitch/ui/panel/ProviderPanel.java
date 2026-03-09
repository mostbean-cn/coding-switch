package com.github.mostbean.codingswitch.ui.panel;

import com.github.mostbean.codingswitch.model.CliType;
import com.github.mostbean.codingswitch.model.Provider;
import com.github.mostbean.codingswitch.service.I18n;
import com.github.mostbean.codingswitch.service.ProviderConnectionTestService;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class ProviderPanel extends JPanel {

    private final ProviderTableModel tableModel = new ProviderTableModel();
    private final JBTable providerTable = new JBTable(tableModel);
    private final JComboBox<CliType> filterCombo = new JComboBox<>();

    public ProviderPanel() {
        setLayout(new BorderLayout());
        setBorder(JBUI.Borders.empty(8));

        add(createToolbar(), BorderLayout.NORTH);
        add(createTablePanel(), BorderLayout.CENTER);

        ProviderService.getInstance().addChangeListener(this::refreshTable);
        refreshTable();
    }

    private JPanel createToolbar() {
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        toolbar.add(new JBLabel(I18n.t("provider.filter.label")));

        filterCombo.addItem(null);
        for (CliType cli : CliType.values()) {
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
        filterCombo.addActionListener(e -> refreshTable());
        toolbar.add(filterCombo);

        return toolbar;
    }

    private JComponent createTablePanel() {
        providerTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        providerTable.getEmptyText().setText(I18n.t("provider.table.empty"));
        providerTable.setRowHeight(JBUI.scale(24));

        providerTable.getColumnModel().getColumn(0).setPreferredWidth(150);
        providerTable.getColumnModel().getColumn(1).setPreferredWidth(100);
        providerTable.getColumnModel().getColumn(2).setPreferredWidth(80);
        providerTable.getColumnModel().getColumn(3).setPreferredWidth(250);

        providerTable.getColumnModel().getColumn(2).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value,
                                                           boolean isSelected, boolean hasFocus,
                                                           int row, int column) {
                Component component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                if (!isSelected) {
                    String status = String.valueOf(value);
                    if (isPositiveStatus(status)) {
                        component.setForeground(new Color(66, 160, 83));
                        setFont(getFont().deriveFont(Font.BOLD));
                    } else if (I18n.t("provider.status.pendingActivation").equals(status)) {
                        component.setForeground(new Color(245, 158, 11));
                        setFont(getFont().deriveFont(Font.BOLD));
                    } else {
                        component.setForeground(table.getForeground());
                        setFont(table.getFont());
                    }
                }
                setHorizontalAlignment(SwingConstants.CENTER);
                return component;
            }
        });

        providerTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && providerTable.getSelectedRow() != -1) {
                    onEdit();
                }
            }
        });

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
                .addExtraAction(new AnAction(I18n.t("provider.action.activate"),
                        I18n.t("provider.action.activate.tooltip"), AllIcons.Actions.Execute) {
                    @Override
                    public void actionPerformed(@NotNull AnActionEvent e) {
                        onPrimaryAction();
                    }

                    @Override
                    public void update(@NotNull AnActionEvent e) {
                        Provider selected = getSelectedProvider();
                        e.getPresentation().setEnabled(selected != null);
                        if (selected == null) {
                            e.getPresentation().setText(I18n.t("provider.action.activate"));
                            e.getPresentation().setDescription(I18n.t("provider.action.activate.tooltip"));
                            return;
                        }
                        e.getPresentation().setText(getPrimaryActionText(selected));
                        e.getPresentation().setDescription(getPrimaryActionTooltip(selected));
                    }
                });

        return decorator.createPanel();
    }

    private void onAdd() {
        ProviderDialog dialog = new ProviderDialog(null);
        if (!dialog.showAndGet()) {
            return;
        }
        try {
            ProviderService.getInstance().addProvider(dialog.getProvider());
        } catch (IOException ex) {
            showActionError(ex);
        }
    }

    private void onEdit() {
        Provider selected = getSelectedProvider();
        if (selected == null) {
            return;
        }
        ProviderDialog dialog = new ProviderDialog(selected);
        if (!dialog.showAndGet()) {
            return;
        }
        try {
            ProviderService.getInstance().updateProvider(dialog.getProvider());
        } catch (IOException ex) {
            showActionError(ex);
        }
    }

    private void onDelete() {
        Provider selected = getSelectedProvider();
        if (selected == null) {
            return;
        }
        int result = Messages.showYesNoDialog(
                I18n.t("provider.dialog.deleteConfirm", selected.getName()),
                I18n.t("provider.dialog.deleteTitle"),
                Messages.getQuestionIcon());
        if (result != Messages.YES) {
            return;
        }
        try {
            ProviderService.getInstance().removeProvider(selected.getId());
        } catch (IOException ex) {
            showActionError(ex);
        }
    }

    private void onDuplicate() {
        Provider selected = getSelectedProvider();
        if (selected == null) {
            return;
        }
        try {
            ProviderService.getInstance().duplicateProvider(selected.getId());
        } catch (IOException ex) {
            showActionError(ex);
        }
    }

    private void onPrimaryAction() {
        Provider selected = getSelectedProvider();
        if (selected == null) {
            return;
        }

        if (selected.getCliType() == CliType.OPENCODE) {
            handleOpenCodeAction(selected);
            return;
        }

        ProviderConnectionTestService.TestResult testResult = ProviderConnectionTestService.getInstance()
                .test(selected.getCliType(), selected.getSettingsConfig());
        if (!testResult.success()) {
            int choice = Messages.showYesNoDialog(
                    I18n.t("provider.dialog.precheckFailed", testResult.message()),
                    I18n.t("provider.dialog.precheckFailedTitle"),
                    I18n.t("provider.dialog.precheckContinue"),
                    I18n.t("provider.dialog.precheckCancel"),
                    Messages.getWarningIcon());
            if (choice != Messages.YES) {
                return;
            }
        }

        try {
            ProviderService.getInstance().activateProvider(selected.getId());
            Messages.showInfoMessage(
                    I18n.t("provider.dialog.activateSuccess", selected.getName(),
                            selected.getCliType().getDisplayName()),
                    I18n.t("provider.dialog.activateTitle"));
        } catch (IOException ex) {
            Messages.showErrorDialog(I18n.t("provider.dialog.activateFailed", ex.getMessage()),
                    I18n.t("provider.dialog.error"));
        }
    }

    private void handleOpenCodeAction(Provider selected) {
        ProviderService service = ProviderService.getInstance();
        boolean enabled = selected.isOpenCodeCustomCategory()
                ? service.isOpenCodeProviderSynced(selected)
                : service.isOpenCodeOmoApplied(selected);
        try {
            if (enabled) {
                service.deactivateProvider(selected.getId());
            } else {
                service.activateProvider(selected.getId());
            }
        } catch (IOException ex) {
            showActionError(ex);
        }
    }

    private void showActionError(IOException ex) {
        Messages.showErrorDialog(I18n.t("provider.dialog.actionFailed", ex.getMessage()),
                I18n.t("provider.dialog.error"));
    }

    private String getPrimaryActionText(Provider provider) {
        if (provider.getCliType() != CliType.OPENCODE) {
            return I18n.t("provider.action.activate");
        }
        if (provider.isOpenCodeCustomCategory()) {
            return ProviderService.getInstance().isOpenCodeProviderSynced(provider)
                    ? I18n.t("provider.action.removeSync")
                    : I18n.t("provider.action.sync");
        }
        return ProviderService.getInstance().isOpenCodeOmoApplied(provider)
                ? I18n.t("provider.action.disable")
                : I18n.t("provider.action.apply");
    }

    private String getPrimaryActionTooltip(Provider provider) {
        if (provider.getCliType() != CliType.OPENCODE) {
            return I18n.t("provider.action.activate.tooltip");
        }
        if (provider.isOpenCodeCustomCategory()) {
            return ProviderService.getInstance().isOpenCodeProviderSynced(provider)
                    ? I18n.t("provider.action.removeSync.tooltip")
                    : I18n.t("provider.action.sync.tooltip");
        }
        return ProviderService.getInstance().isOpenCodeOmoApplied(provider)
                ? I18n.t("provider.action.disable.tooltip")
                : I18n.t("provider.action.apply.tooltip");
    }

    private boolean isPositiveStatus(String status) {
        return I18n.t("provider.status.active").equals(status)
                || I18n.t("provider.status.synced").equals(status)
                || I18n.t("provider.status.applied").equals(status);
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

    private static class ProviderTableModel extends AbstractTableModel {
        private final String[] columns = {
                I18n.t("provider.table.col.name"),
                I18n.t("provider.table.col.cli"),
                I18n.t("provider.table.col.status"),
                I18n.t("provider.table.col.model")
        };
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
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            Provider provider = data.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> provider.getName();
                case 1 -> provider.getCliType().getDisplayName();
                case 2 -> resolveStatusText(provider);
                case 3 -> extractModelName(provider.getCliType(), provider.getSettingsConfig());
                default -> "";
            };
        }

        private String resolveStatusText(Provider provider) {
            if (provider.getCliType() == CliType.OPENCODE) {
                ProviderService service = ProviderService.getInstance();
                if (provider.isOpenCodeCustomCategory()) {
                    return service.isOpenCodeProviderSynced(provider)
                            ? I18n.t("provider.status.synced")
                            : I18n.t("provider.status.notSynced");
                }
                return service.isOpenCodeOmoApplied(provider)
                        ? I18n.t("provider.status.applied")
                        : I18n.t("provider.status.notApplied");
            }
            if (provider.isPendingActivation()) {
                return I18n.t("provider.status.pendingActivation");
            }
            return provider.isActive() ? I18n.t("provider.status.active") : "-";
        }

        private String extractModelName(CliType cliType, JsonObject config) {
            if (config == null) {
                return "N/A";
            }
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
                    case OPENCODE -> extractOpenCodeModelName(config);
                };
            } catch (Exception e) {
                return "Parsing Error";
            }
        }

        private String extractOpenCodeModelName(JsonObject config) {
            if (config.has("models") && config.get("models").isJsonObject()
                    && !config.getAsJsonObject("models").keySet().isEmpty()) {
                return String.join(", ", config.getAsJsonObject("models").keySet());
            }

            Set<String> models = new LinkedHashSet<>();
            collectOmoModels(config, "agents", models);
            collectOmoModels(config, "categories", models);
            if (!models.isEmpty()) {
                return String.join(", ", models.stream().limit(3).toList());
            }
            return "N/A";
        }

        private void collectOmoModels(JsonObject config, String rootKey, Set<String> models) {
            if (!config.has(rootKey) || !config.get(rootKey).isJsonObject()) {
                return;
            }
            JsonObject entries = config.getAsJsonObject(rootKey);
            for (String key : entries.keySet()) {
                if (!entries.get(key).isJsonObject()) {
                    continue;
                }
                JsonObject entry = entries.getAsJsonObject(key);
                if (entry.has("model") && !entry.get("model").isJsonNull()) {
                    models.add(entry.get("model").getAsString());
                }
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
