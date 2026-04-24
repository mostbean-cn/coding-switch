package com.github.mostbean.codingswitch.ui.panel;

import com.github.mostbean.codingswitch.model.CliType;
import com.github.mostbean.codingswitch.model.McpServer;
import com.github.mostbean.codingswitch.service.I18n;
import com.github.mostbean.codingswitch.service.McpService;
import com.github.mostbean.codingswitch.service.PluginSettings;
import com.github.mostbean.codingswitch.ui.dialog.McpServerDialog;
import com.google.gson.Gson;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * MCP 服务器管理面板。
 */
public class McpPanel extends JPanel {

    private final CliType[] cliTypes = PluginSettings.getInstance().getVisibleManagedCliTypes().toArray(CliType[]::new);
    private final McpTableModel tableModel = new McpTableModel(cliTypes, this::markDirty);
    private final JBTable serverTable = new JBTable(tableModel);
    private final Path projectRoot;

    private boolean isDirty = false;
    private AnAction saveAction;

    public McpPanel(Project project) {
        this.projectRoot = resolveProjectRoot(project);

        setLayout(new BorderLayout());
        setBorder(JBUI.Borders.empty(8));

        add(createTablePanel(), BorderLayout.CENTER);

        McpService.getInstance().addChangeListener(this::refreshTable);
        refreshTable();
    }

    private static Path resolveProjectRoot(Project project) {
        if (project == null || project.getBasePath() == null || project.getBasePath().isBlank()) {
            return null;
        }
        try {
            return Path.of(project.getBasePath()).toAbsolutePath().normalize();
        } catch (Exception ignored) {
            return null;
        }
    }

    private JComponent createTablePanel() {
        serverTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        serverTable.getEmptyText().setText(I18n.t("mcp.table.empty"));
        serverTable.setRowHeight(JBUI.scale(28));

        serverTable.getColumnModel().getColumn(0).setPreferredWidth(130);
        serverTable.getColumnModel().getColumn(1).setPreferredWidth(60);
        for (int i = 0; i < cliTypes.length; i++) {
            serverTable.getColumnModel().getColumn(2 + i).setPreferredWidth(70);
            serverTable.getColumnModel().getColumn(2 + i).setMaxWidth(90);
        }
        int detailCol = 2 + cliTypes.length;
        serverTable.getColumnModel().getColumn(detailCol).setPreferredWidth(250);

        DefaultTableCellRenderer centerRenderer = new DefaultTableCellRenderer();
        centerRenderer.setHorizontalAlignment(SwingConstants.CENTER);
        serverTable.getColumnModel().getColumn(1).setCellRenderer(centerRenderer);

        serverTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int col = serverTable.columnAtPoint(e.getPoint());
                    if (col < 2 || col >= 2 + cliTypes.length) {
                        if (serverTable.getSelectedRow() != -1) {
                            onEdit();
                        }
                    }
                }
            }
        });

        saveAction = new AnAction(I18n.t("mcp.action.save"), I18n.t("mcp.action.save.tooltip"),
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

        ToolbarDecorator decorator = ToolbarDecorator.createDecorator(serverTable)
                .setAddAction(button -> onAdd())
                .setEditAction(button -> onEdit())
                .setRemoveAction(button -> onDelete())
                .addExtraAction(saveAction)
                .addExtraAction(new AnAction(I18n.t("mcp.action.importCli"), I18n.t("mcp.action.importCli.tooltip"),
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
        serverTable.repaint();
    }

    private void onAdd() {
        McpServerDialog dialog = new McpServerDialog(null);
        if (dialog.showAndGet()) {
            if (dialog.isMultipleServers()) {
                for (McpServer s : dialog.getServers()) {
                    McpService.getInstance().addServer(s);
                }
                Messages.showInfoMessage(I18n.t("mcp.import.batchDone", dialog.getServers().size()),
                        I18n.t("mcp.dialog.importDone"));
            } else {
                McpService.getInstance().addServer(dialog.getServer());
            }
        }
    }

    private void onEdit() {
        McpServer selected = getSelectedServer();
        if (selected == null) {
            return;
        }
        McpServerDialog dialog = new McpServerDialog(selected);
        if (dialog.showAndGet()) {
            McpService.getInstance().updateServer(dialog.getServer());
        }
    }

    private void onDelete() {
        McpServer selected = getSelectedServer();
        if (selected == null) {
            return;
        }
        int result = Messages.showYesNoDialog(
                I18n.t("mcp.dialog.deleteConfirm", selected.getName()),
                I18n.t("mcp.dialog.deleteTitle"),
                Messages.getQuestionIcon());
        if (result == Messages.YES) {
            McpService.getInstance().removeServer(selected.getId());
        }
    }

    private void onSaveChanges() {
        if (!isDirty) {
            return;
        }
        List<McpServer> currentList = tableModel.getServers();
        for (McpServer s : currentList) {
            McpService.getInstance().updateServer(s);
        }
        isDirty = false;
        Messages.showInfoMessage(I18n.t("mcp.dialog.saveSuccess"), I18n.t("mcp.dialog.saveTitle"));
    }

    private void onImportFromCli() {
        McpService.ImportOptions options = new McpService.ImportOptions();
        McpService.ImportReport report = McpService.getInstance().importFromCliConfigs(projectRoot, options);

        StringBuilder sb = new StringBuilder();
        sb.append(I18n.t("mcp.import.newlyImported", report.newlyImported)).append("\n");
        sb.append(I18n.t("mcp.import.mergedExisting", report.mergedExisting)).append("\n");
        sb.append(I18n.t("mcp.import.skippedInvalid", report.skippedInvalid)).append("\n\n");
        sb.append(I18n.t("mcp.import.nextStep"));

        if (!report.warnings.isEmpty()) {
            sb.append("\n\n").append(I18n.t("mcp.import.warnings")).append("\n");
            int max = Math.min(5, report.warnings.size());
            for (int i = 0; i < max; i++) {
                sb.append("- ").append(report.warnings.get(i)).append("\n");
            }
            if (report.warnings.size() > max) {
                sb.append(I18n.t("mcp.import.moreWarnings", report.warnings.size() - max));
            }
        }

        Messages.showInfoMessage(sb.toString(), I18n.t("mcp.dialog.importDone"));
    }

    private McpServer getSelectedServer() {
        int row = serverTable.getSelectedRow();
        if (row >= 0) {
            return tableModel.getServerAt(row);
        }
        return null;
    }

    private void refreshTable() {
        List<McpServer> clones = new ArrayList<>();
        Gson gson = new Gson();
        for (McpServer s : McpService.getInstance().getServers()) {
            clones.add(gson.fromJson(gson.toJson(s), McpServer.class));
        }
        tableModel.setServers(clones);
        isDirty = false;
    }

    private static class McpTableModel extends AbstractTableModel {

        private static final int COL_NAME = 0;
        private static final int COL_TRANSPORT = 1;
        private static final int COL_CLI_START = 2;
        private static final int COL_DETAIL_BASE = COL_CLI_START;
        private final CliType[] cliTypes;
        private final Runnable dirtyCallback;
        private List<McpServer> data = new ArrayList<>();

        public McpTableModel(CliType[] cliTypes, Runnable dirtyCallback) {
            this.cliTypes = cliTypes;
            this.dirtyCallback = dirtyCallback;
        }

        private int detailColumn() {
            return COL_DETAIL_BASE + cliTypes.length;
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
            return detailColumn() + 1;
        }

        @Override
        public String getColumnName(int column) {
            if (column == COL_NAME) {
                return I18n.t("mcp.table.col.name");
            }
            if (column == COL_TRANSPORT) {
                return I18n.t("mcp.table.col.transport");
            }
            int detailColumn = detailColumn();
            if (column >= COL_CLI_START && column < detailColumn) {
                return cliTypes[column - COL_CLI_START].getDisplayName();
            }
            if (column == detailColumn) {
                return I18n.t("mcp.table.col.detail");
            }
            return "";
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            if (columnIndex >= COL_CLI_START && columnIndex < detailColumn()) {
                return Boolean.class;
            }
            return String.class;
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return columnIndex >= COL_CLI_START && columnIndex < detailColumn();
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            McpServer s = data.get(rowIndex);
            if (columnIndex == COL_NAME) {
                return (s.isEnabled() ? "" : "⚪") + s.getName();
            }
            if (columnIndex == COL_TRANSPORT) {
                return s.getTransportType().name();
            }
            int detailColumn = detailColumn();
            if (columnIndex >= COL_CLI_START && columnIndex < detailColumn) {
                CliType cli = cliTypes[columnIndex - COL_CLI_START];
                return s.isSyncedTo(cli);
            }
            if (columnIndex == detailColumn) {
                if (s.getTransportType() == McpServer.TransportType.STDIO) {
                    return s.getCommand() + (s.getArgs() != null && s.getArgs().length > 0
                            ? " " + String.join(" ", s.getArgs())
                            : "");
                }
                return s.getUrl() != null ? s.getUrl() : "";
            }
            return "";
        }

        @Override
        public void setValueAt(Object aValue, int rowIndex, int columnIndex) {
            if (columnIndex >= COL_CLI_START && columnIndex < detailColumn()) {
                McpServer s = data.get(rowIndex);
                CliType cli = cliTypes[columnIndex - COL_CLI_START];
                boolean enabled = Boolean.TRUE.equals(aValue);
                if (s.isSyncedTo(cli) != enabled) {
                    s.setSyncedTo(cli, enabled);
                    dirtyCallback.run();
                }
            }
        }
    }
}
