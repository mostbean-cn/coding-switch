package com.github.mostbean.codingswitch.ui.dialog;

import com.github.mostbean.codingswitch.service.I18n;
import com.github.mostbean.codingswitch.service.SkillService;
import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

public class SkillDiscoveryDialog extends DialogWrapper {

    private final SkillTableModel tableModel = new SkillTableModel();
    private final JBTable table = new JBTable(tableModel);
    private final JBLabel statusLabel = new JBLabel(" ");

    private final ComboBox<RepoItem> repoComboBox = new ComboBox<>();
    private final JButton addRepoButton = new JButton(I18n.t("skill.action.addRepo"));
    private final JButton refreshButton = new JButton(I18n.t("skill.discovery.button.refresh"));
    private final JButton installButton = new JButton(I18n.t("skill.discovery.button.install"));
    private final JButton openButton = new JButton(I18n.t("skill.discovery.button.open"));

    private volatile boolean loading = false;
    private volatile boolean suppressRepoSelectionEvent = false;
    private SkillService.RepoDiscoveryInfo currentRepoInfo;

    public SkillDiscoveryDialog() {
        super(true);
        setTitle(I18n.t("skill.discovery.title"));
        setOKButtonText(I18n.t("skill.discovery.button.close"));
        addRepoButton.setToolTipText(I18n.t("skill.action.addRepo.tooltip"));

        configureTable();
        bindActions();

        init();
        refreshRepositories();
    }

    @Override
    protected Action[] createActions() {
        return new Action[] { getOKAction() };
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel panel = new JPanel(new BorderLayout(JBUI.scale(8), JBUI.scale(8)));
        panel.setPreferredSize(new Dimension(JBUI.scale(780), JBUI.scale(360)));
        panel.setBorder(JBUI.Borders.empty(6));

        JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0));
        topPanel.add(new JBLabel(I18n.t("skill.discovery.label.repo")));
        topPanel.add(repoComboBox);
        topPanel.add(addRepoButton);
        panel.add(topPanel, BorderLayout.NORTH);

        panel.add(new JScrollPane(table), BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout(JBUI.scale(8), 0));
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0));
        actions.add(refreshButton);
        actions.add(installButton);
        actions.add(openButton);
        bottom.add(actions, BorderLayout.WEST);

        statusLabel.setBorder(JBUI.Borders.emptyLeft(8));
        bottom.add(statusLabel, BorderLayout.CENTER);
        panel.add(bottom, BorderLayout.SOUTH);

        return panel;
    }

    private void configureTable() {
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setRowHeight(JBUI.scale(26));
        table.setAutoCreateRowSorter(true);

        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                updateButtons();
            }
        });

        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    onInstallSelected();
                }
            }
        });
    }

    private void bindActions() {
        repoComboBox.addItemListener(e -> {
            if (!suppressRepoSelectionEvent && e.getStateChange() == java.awt.event.ItemEvent.SELECTED) {
                loadRepository(false);
            }
        });
        refreshButton.addActionListener(e -> loadRepository(true));
        addRepoButton.addActionListener(e -> onAddRepository());
        installButton.addActionListener(e -> onInstallSelected());
        openButton.addActionListener(e -> onOpenSelected());
        updateButtons();
    }

    private void refreshRepositories() {
        reloadRepositoryOptions(null);
        loadRepository(false);
    }

    private void loadRepository(boolean forceRefresh) {
        RepoItem selectedItem = (RepoItem) repoComboBox.getSelectedItem();
        if (selectedItem == null) {
            currentRepoInfo = null;
            tableModel.setRows(new ArrayList<>());
            updateButtons();
            return;
        }

        setLoading(true, I18n.t("skill.discovery.status.loading"));
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            SkillService.RepoDiscoveryInfo repoInfo;
            String status;
            try {
                repoInfo = SkillService.getInstance().discoverSkillRepository(selectedItem.url, forceRefresh);
                if (repoInfo.errorMessage() != null && !repoInfo.errorMessage().isBlank()) {
                    if (isRateLimited(repoInfo.errorMessage())) {
                        status = I18n.t("skill.discovery.status.rateLimited");
                    } else {
                        status = I18n.t("skill.discovery.status.loadFailed", repoInfo.errorMessage());
                    }
                } else {
                    status = I18n.t("skill.discovery.status.loaded", repoInfo.skillCount());
                }
            } catch (Exception ex) {
                String reason = ex.getMessage() == null || ex.getMessage().isBlank()
                        ? ex.getClass().getSimpleName()
                        : ex.getMessage();
                status = I18n.t("skill.discovery.status.loadFailed", reason);
                repoInfo = null;
            }

            SkillService.RepoDiscoveryInfo finalRepoInfo = repoInfo;
            String finalStatus = status;
            ApplicationManager.getApplication().invokeLater(() -> {
                currentRepoInfo = finalRepoInfo;
                if (finalRepoInfo != null && finalRepoInfo.skillNames() != null) {
                    tableModel.setRows(finalRepoInfo.skillNames());
                } else {
                    tableModel.setRows(new ArrayList<>());
                }
                if (tableModel.getRowCount() > 0) {
                    table.setRowSelectionInterval(0, 0);
                }
                setLoading(false, finalStatus);
            }, ModalityState.any());
        });
    }

    private void onInstallSelected() {
        String skillName = getSelectedSkill();
        if (skillName == null || currentRepoInfo == null) {
            return;
        }

        SkillService.RepoDiscoveryInfo singleSkillRepo = new SkillService.RepoDiscoveryInfo(
                currentRepoInfo.repositoryUrl(),
                currentRepoInfo.owner(),
                currentRepoInfo.repo(),
                currentRepoInfo.branch(),
                List.of(skillName),
                null);

        setLoading(true, I18n.t("skill.discovery.status.installing", skillName));
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            SkillService.RepoInstallResult result;
            try {
                result = SkillService.getInstance().installSkillsFromRepository(singleSkillRepo);
            } catch (Exception ex) {
                String reason = ex.getMessage() == null || ex.getMessage().isBlank()
                        ? ex.getClass().getSimpleName()
                        : ex.getMessage();
                result = new SkillService.RepoInstallResult(false, 0, 0, 0, reason);
            }
            SkillService.RepoInstallResult finalResult = result;
            ApplicationManager.getApplication().invokeLater(() -> {
                setLoading(false, I18n.t("skill.discovery.status.installDone", finalResult.installed(),
                        finalResult.skipped(), finalResult.failed()));
                if (finalResult.success()) {
                    Messages.showInfoMessage(
                            I18n.t("skill.discovery.install.success", skillName, finalResult.installed(),
                                    finalResult.skipped(), finalResult.failed()),
                            I18n.t("skill.discovery.install.title"));
                } else {
                    Messages.showErrorDialog(
                            I18n.t("skill.discovery.install.failed", skillName, finalResult.message()),
                            I18n.t("provider.dialog.error"));
                }
            }, ModalityState.any());
        });
    }

    private void onOpenSelected() {
        if (currentRepoInfo == null) {
            return;
        }
        String skillName = getSelectedSkill();
        String url;
        if (skillName != null) {
            if (currentRepoInfo.skillCount() == 1 && skillName.equals(currentRepoInfo.repo())) {
                url = currentRepoInfo.repositoryUrl();
            } else {
                String b = (currentRepoInfo.branch() == null || currentRepoInfo.branch().isBlank()) ? "main"
                        : currentRepoInfo.branch();
                String base = currentRepoInfo.repositoryUrl() == null ? ""
                        : currentRepoInfo.repositoryUrl().replaceAll("/+$", "");
                url = base + "/tree/" + b + "/skills/" + skillName;
            }
        } else {
            url = currentRepoInfo.skillCount() > 0 ? currentRepoInfo.skillsPageUrl() : currentRepoInfo.repositoryUrl();
        }
        BrowserUtil.browse(url);
    }

    private void onAddRepository() {
        String input = Messages.showInputDialog(
                getContentPanel(),
                I18n.t("skill.dialog.addRepoPrompt"),
                I18n.t("skill.dialog.addRepoTitle"),
                Messages.getQuestionIcon());
        if (input == null) {
            return;
        }
        String repoUrl = normalizeRepositoryInput(input);
        if (repoUrl == null) {
            Messages.showErrorDialog(
                    I18n.t("skill.discovery.status.loadFailed", "Invalid GitHub repository URL"),
                    I18n.t("provider.dialog.error"));
            return;
        }
        SkillService.getInstance().addCustomRepo(repoUrl);
        reloadRepositoryOptions(repoUrl);
        loadRepository(true);
        Messages.showInfoMessage(
                I18n.t("skill.dialog.addRepoDone", repoUrl),
                I18n.t("skill.dialog.addRepoSuccess"));
    }

    private void reloadRepositoryOptions(@Nullable String preferredRepoUrl) {
        RepoItem selectedItem = (RepoItem) repoComboBox.getSelectedItem();
        String targetRepoUrl = preferredRepoUrl;
        if ((targetRepoUrl == null || targetRepoUrl.isBlank()) && selectedItem != null) {
            targetRepoUrl = selectedItem.url;
        }

        LinkedHashSet<String> uniqueUrls = new LinkedHashSet<>();
        for (String repoUrl : SkillService.getInstance().getAllRepos()) {
            if (repoUrl == null || repoUrl.isBlank()) {
                continue;
            }
            uniqueUrls.add(repoUrl.trim());
        }

        suppressRepoSelectionEvent = true;
        try {
            DefaultComboBoxModel<RepoItem> model = new DefaultComboBoxModel<>();
            RepoItem matchedItem = null;
            for (String repoUrl : uniqueUrls) {
                RepoItem item = new RepoItem(repoUrl);
                model.addElement(item);
                if (targetRepoUrl != null && targetRepoUrl.equalsIgnoreCase(repoUrl)) {
                    matchedItem = item;
                }
            }
            repoComboBox.setModel(model);
            if (matchedItem != null) {
                repoComboBox.setSelectedItem(matchedItem);
            } else if (model.getSize() > 0) {
                repoComboBox.setSelectedIndex(0);
            }
        } finally {
            suppressRepoSelectionEvent = false;
        }
    }

    private static @Nullable String normalizeRepositoryInput(String rawInput) {
        if (rawInput == null) {
            return null;
        }
        String value = rawInput.trim();
        if (value.isBlank()) {
            return null;
        }
        if (value.matches("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")) {
            value = "https://github.com/" + value;
        }
        if (value.endsWith(".git")) {
            value = value.substring(0, value.length() - 4);
        }

        String marker = "github.com/";
        int index = value.toLowerCase(java.util.Locale.ROOT).indexOf(marker);
        if (index < 0) {
            return null;
        }
        String suffix = value.substring(index + marker.length());
        String[] parts = suffix.split("/");
        if (parts.length < 2) {
            return null;
        }
        String owner = parts[0];
        String repo = parts[1].split("[?#]", 2)[0];
        if (owner.isBlank() || repo.isBlank()) {
            return null;
        }
        if (repo.endsWith(".git")) {
            repo = repo.substring(0, repo.length() - 4);
        }
        return "https://github.com/" + owner + "/" + repo;
    }

    private void setLoading(boolean loading, String status) {
        this.loading = loading;
        statusLabel.setText(status == null ? " " : status);
        updateButtons();
    }

    private void updateButtons() {
        String selected = getSelectedSkill();
        boolean hasSelection = selected != null;

        repoComboBox.setEnabled(!loading);
        addRepoButton.setEnabled(!loading);
        refreshButton.setEnabled(!loading);
        openButton.setEnabled(!loading && hasSelection);
        installButton.setEnabled(!loading && hasSelection);
    }

    private String getSelectedSkill() {
        int viewRow = table.getSelectedRow();
        if (viewRow < 0) {
            return null;
        }
        int modelRow = table.convertRowIndexToModel(viewRow);
        return tableModel.getRow(modelRow);
    }

    private static boolean isRateLimited(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String normalized = message.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("rate limit");
    }

    static class RepoItem {
        final String url;
        final String label;

        RepoItem(String url) {
            this.url = url;
            String name = url;
            if (name.startsWith("https://github.com/")) {
                name = name.substring("https://github.com/".length());
            }
            this.label = name;
        }

        @Override
        public String toString() {
            return label;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || getClass() != o.getClass())
                return false;
            RepoItem repoItem = (RepoItem) o;
            return java.util.Objects.equals(url, repoItem.url);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(url);
        }
    }

    private static final class SkillTableModel extends AbstractTableModel {
        private static final String[] COLUMNS = {
                I18n.t("skill.discovery.col.skillName")
        };

        private List<String> rows = new ArrayList<>();

        public void setRows(List<String> rows) {
            this.rows = rows == null ? new ArrayList<>() : new ArrayList<>(rows);
            fireTableDataChanged();
        }

        public String getRow(int index) {
            if (index < 0 || index >= rows.size()) {
                return null;
            }
            return rows.get(index);
        }

        @Override
        public int getRowCount() {
            return rows.size();
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
            return rows.get(rowIndex);
        }
    }
}
