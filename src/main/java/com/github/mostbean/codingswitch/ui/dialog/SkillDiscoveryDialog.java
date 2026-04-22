package com.github.mostbean.codingswitch.ui.dialog;

import com.github.mostbean.codingswitch.service.I18n;
import com.github.mostbean.codingswitch.service.PluginSettings;
import com.github.mostbean.codingswitch.service.SkillService;
import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

public class SkillDiscoveryDialog extends DialogWrapper {

    private final SkillTableModel tableModel = new SkillTableModel();
    private final JBTable table = new JBTable(tableModel);
    private final JBLabel statusLabel = new JBLabel(" ");

    private final ComboBox<RepoItem> repoComboBox = new ComboBox<>();
    private final JButton addRepoButton = new JButton(I18n.t("skill.action.addRepo"));
    private final JButton manageRepoButton = new JButton(I18n.t("skill.action.manageRepo"));
    private final JButton configTokenButton = new JButton(I18n.t("skill.action.configToken"));
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
        manageRepoButton.setToolTipText(I18n.t("skill.action.manageRepo.tooltip"));
        configTokenButton.setToolTipText(I18n.t("skill.action.configToken.tooltip"));

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
        topPanel.add(manageRepoButton);
        topPanel.add(configTokenButton);
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
        manageRepoButton.addActionListener(e -> onManageRepositories());
        configTokenButton.addActionListener(e -> onConfigToken());
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
                repoInfo = SkillService.getInstance().discoverSkillRepository(selectedItem.url, selectedItem.branch,
                        forceRefresh);
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
        if (currentRepoInfo == null || currentRepoInfo.skillCount() <= 0) {
            return;
        }

        String repoName = currentRepoInfo.displayName();
        SkillService.RepoDiscoveryInfo repoToInstall = currentRepoInfo;
        setLoading(true, I18n.t("skill.discovery.status.installing", repoName));
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            SkillService.RepoInstallResult result;
            try {
                result = SkillService.getInstance().installSkillsFromRepository(repoToInstall);
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
                            I18n.t("skill.discovery.install.success", repoName, finalResult.installed(),
                                    finalResult.skipped(), finalResult.failed()),
                            I18n.t("skill.discovery.install.title"));
                } else {
                    Messages.showErrorDialog(
                            I18n.t("skill.discovery.install.failed", repoName, finalResult.message()),
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
        RepoInput input = promptRepositoryInput(null);
        if (input == null) {
            return;
        }

        SkillService.getInstance().addOrUpdateCustomRepo(input.repositoryUrl(), input.branch());
        reloadRepositoryOptions(new RepoItem(input.repositoryUrl(), input.branch(), false));
        loadRepository(true);

        String displayName = input.branch() == null ? input.repositoryUrl()
                : input.repositoryUrl() + "@" + input.branch();
        Messages.showInfoMessage(
                I18n.t("skill.dialog.addRepoDone", displayName),
                I18n.t("skill.dialog.addRepoSuccess"));
    }

    private void onManageRepositories() {
        RepoManagerDialog dialog = new RepoManagerDialog();
        if (!dialog.showAndGet() || !dialog.hasChanged()) {
            return;
        }
        reloadRepositoryOptions(dialog.getPreferredRepoItem());
        loadRepository(true);
    }

    private void onConfigToken() {
        TokenConfigDialog dialog = new TokenConfigDialog();
        dialog.show();
    }

    private @Nullable RepoInput promptRepositoryInput(@Nullable RepoInput preset) {
        JTextField urlField = new JTextField(preset == null ? "" : preset.repositoryUrl(), 38);
        JTextField branchField = new JTextField(preset == null || preset.branch() == null ? "" : preset.branch(), 24);

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = JBUI.insets(4);
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.anchor = GridBagConstraints.WEST;
        form.add(new JLabel(I18n.t("skill.dialog.repo.urlLabel")), gbc);

        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        form.add(urlField, gbc);

        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        form.add(new JLabel(I18n.t("skill.dialog.repo.branchLabel")), gbc);

        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        form.add(branchField, gbc);

        gbc.gridx = 1;
        gbc.gridy = 2;
        gbc.fill = GridBagConstraints.NONE;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.weightx = 0;
        form.add(new JBLabel(I18n.t("skill.dialog.repo.branchHint")), gbc);

        Object[] options = { I18n.t("common.button.ok"), I18n.t("common.button.cancel") };
        int result = JOptionPane.showOptionDialog(
                getContentPanel(),
                form,
                I18n.t("skill.dialog.addRepoTitle"),
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE,
                null,
                options,
                options[0]);
        if (result != JOptionPane.OK_OPTION) {
            return null;
        }

        String repoUrl = normalizeRepositoryInput(urlField.getText());
        if (repoUrl == null) {
            Messages.showErrorDialog(
                    I18n.t("skill.discovery.status.loadFailed", "Invalid GitHub repository URL"),
                    I18n.t("provider.dialog.error"));
            return null;
        }

        return new RepoInput(repoUrl, normalizeBranchInput(branchField.getText()));
    }

    private void reloadRepositoryOptions(@Nullable RepoItem preferredRepoItem) {
        RepoItem selectedItem = (RepoItem) repoComboBox.getSelectedItem();
        String targetKey = preferredRepoItem != null ? preferredRepoItem.key() : null;
        if ((targetKey == null || targetKey.isBlank()) && selectedItem != null) {
            targetKey = selectedItem.key();
        }

        LinkedHashMap<String, RepoItem> uniqueItems = new LinkedHashMap<>();
        for (SkillService.RepoOption repoOption : SkillService.getInstance().getAllRepoOptions()) {
            if (repoOption == null || repoOption.repositoryUrl() == null || repoOption.repositoryUrl().isBlank()) {
                continue;
            }
            RepoItem item = new RepoItem(repoOption);
            uniqueItems.putIfAbsent(item.key(), item);
        }

        suppressRepoSelectionEvent = true;
        try {
            DefaultComboBoxModel<RepoItem> model = new DefaultComboBoxModel<>();
            RepoItem matchedItem = null;
            for (RepoItem item : uniqueItems.values()) {
                model.addElement(item);
                if (targetKey != null && targetKey.equals(item.key())) {
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

    private static @Nullable String normalizeBranchInput(String rawInput) {
        if (rawInput == null) {
            return null;
        }
        String value = rawInput.trim();
        if (value.isBlank()) {
            return null;
        }
        String prefix = "refs/heads/";
        if (value.startsWith(prefix) && value.length() > prefix.length()) {
            value = value.substring(prefix.length());
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value.isBlank() ? null : value;
    }

    private void setLoading(boolean loading, String status) {
        this.loading = loading;
        statusLabel.setText(status == null ? " " : status);
        updateButtons();
    }

    private void updateButtons() {
        String selected = getSelectedSkill();
        boolean hasSelection = selected != null;
        boolean hasInstallableRepository = currentRepoInfo != null && currentRepoInfo.skillCount() > 0;

        repoComboBox.setEnabled(!loading);
        addRepoButton.setEnabled(!loading);
        manageRepoButton.setEnabled(!loading);
        configTokenButton.setEnabled(!loading);
        refreshButton.setEnabled(!loading);
        openButton.setEnabled(!loading && hasSelection);
        installButton.setEnabled(!loading && hasInstallableRepository);
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

    private static String buildRepoKey(String repoUrl, @Nullable String branch) {
        String normalizedUrl = repoUrl == null ? "" : repoUrl.trim().toLowerCase(java.util.Locale.ROOT);
        String normalizedBranch = normalizeBranchInput(branch);
        return normalizedUrl + "#"
                + (normalizedBranch == null ? "" : normalizedBranch.toLowerCase(java.util.Locale.ROOT));
    }

    private record RepoInput(String repositoryUrl, @Nullable String branch) {
    }

    static class RepoItem {
        final String url;
        final @Nullable String branch;
        final boolean builtIn;
        final String label;

        RepoItem(SkillService.RepoOption repoOption) {
            this(repoOption.repositoryUrl(), repoOption.branch(), repoOption.builtIn());
        }

        RepoItem(String url, @Nullable String branch, boolean builtIn) {
            this.url = url;
            this.branch = normalizeBranchInput(branch);
            this.builtIn = builtIn;
            String name = url;
            if (name.startsWith("https://github.com/")) {
                name = name.substring("https://github.com/".length());
            }
            if (this.branch != null) {
                name = name + "@" + this.branch;
            }
            if (this.builtIn) {
                name = name + " (" + I18n.t("skill.repo.manager.builtinTag") + ")";
            }
            this.label = name;
        }

        String key() {
            return buildRepoKey(url, branch);
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
            return key().equals(repoItem.key());
        }

        @Override
        public int hashCode() {
            return key().hashCode();
        }
    }

    private final class RepoManagerDialog extends DialogWrapper {
        private final DefaultListModel<RepoItem> listModel = new DefaultListModel<>();
        private final JBList<RepoItem> repoList = new JBList<>(listModel);
        private final JButton addButton = new JButton(I18n.t("skill.repo.manager.add"));
        private final JButton removeButton = new JButton(I18n.t("skill.repo.manager.remove"));
        private boolean changed;
        private @Nullable RepoItem preferredRepoItem;

        RepoManagerDialog() {
            super(true);
            setTitle(I18n.t("skill.repo.manager.title"));
            setOKButtonText(I18n.t("skill.discovery.button.close"));
            init();

            addButton.addActionListener(e -> onAddRepo());
            removeButton.addActionListener(e -> onRemoveRepo());
            repoList.addListSelectionListener(e -> updateRemoveButton());

            reloadList(null);
            updateRemoveButton();
        }

        @Override
        protected Action[] createActions() {
            return new Action[] { getOKAction() };
        }

        @Override
        protected @Nullable JComponent createCenterPanel() {
            JPanel panel = new JPanel(new BorderLayout(JBUI.scale(8), JBUI.scale(8)));
            panel.setPreferredSize(new Dimension(JBUI.scale(560), JBUI.scale(300)));
            panel.setBorder(JBUI.Borders.empty(6));

            panel.add(new JScrollPane(repoList), BorderLayout.CENTER);

            JPanel actions = new JPanel(new GridLayout(0, 1, 0, JBUI.scale(8)));
            actions.add(addButton);
            actions.add(removeButton);
            panel.add(actions, BorderLayout.EAST);

            return panel;
        }

        private void onAddRepo() {
            RepoInput input = promptRepositoryInput(null);
            if (input == null) {
                return;
            }
            SkillService.getInstance().addOrUpdateCustomRepo(input.repositoryUrl(), input.branch());
            changed = true;
            preferredRepoItem = new RepoItem(input.repositoryUrl(), input.branch(), false);
            reloadList(preferredRepoItem.key());
        }

        private void onRemoveRepo() {
            RepoItem selected = repoList.getSelectedValue();
            if (selected == null) {
                return;
            }
            if (selected.builtIn) {
                Messages.showInfoMessage(
                        I18n.t("skill.repo.manager.removeBuiltIn"),
                        I18n.t("skill.repo.manager.removeTitle"));
                return;
            }

            String displayName = selected.branch == null ? selected.url : selected.url + "@" + selected.branch;
            int result = Messages.showYesNoDialog(
                    I18n.t("skill.repo.manager.removeConfirm", displayName),
                    I18n.t("skill.repo.manager.removeTitle"),
                    Messages.getQuestionIcon());
            if (result != Messages.YES) {
                return;
            }

            SkillService.getInstance().removeCustomRepo(selected.url, selected.branch);
            changed = true;
            preferredRepoItem = null;
            reloadList(null);
        }

        private void reloadList(@Nullable String preferredKey) {
            listModel.clear();
            RepoItem selectedItem = null;
            for (SkillService.RepoOption repoOption : SkillService.getInstance().getAllRepoOptions()) {
                RepoItem item = new RepoItem(repoOption);
                listModel.addElement(item);
                if (preferredKey != null && preferredKey.equals(item.key())) {
                    selectedItem = item;
                }
            }
            if (selectedItem != null) {
                repoList.setSelectedValue(selectedItem, true);
            } else if (!listModel.isEmpty()) {
                repoList.setSelectedIndex(0);
            }
            updateRemoveButton();
        }

        private void updateRemoveButton() {
            RepoItem selected = repoList.getSelectedValue();
            removeButton.setEnabled(selected != null && !selected.builtIn);
        }

        boolean hasChanged() {
            return changed;
        }

        @Nullable
        RepoItem getPreferredRepoItem() {
            if (preferredRepoItem != null) {
                return preferredRepoItem;
            }
            return repoList.getSelectedValue();
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

    /**
     * GitHub 令牌配置对话框
     */
    private final class TokenConfigDialog extends DialogWrapper {
        private JPasswordField tokenField;
        private JToggleButton showToggle;

        TokenConfigDialog() {
            super(true);
            setTitle(I18n.t("skill.tokenConfig.title"));
            setOKButtonText(I18n.t("settings.button.saveGithubToken"));
            init();
        }

        @Override
        protected Action[] createActions() {
            return new Action[] { getOKAction(), getCancelAction() };
        }

        @Override
        protected @Nullable JComponent createCenterPanel() {
            JPanel panel = new JPanel(new GridBagLayout());
            panel.setPreferredSize(new Dimension(JBUI.scale(450), JBUI.scale(120)));
            panel.setBorder(JBUI.Borders.empty(12));

            GridBagConstraints gbc = new GridBagConstraints();
            gbc.insets = JBUI.insets(6, 8);
            gbc.anchor = GridBagConstraints.WEST;

            // 提示信息
            gbc.gridx = 0;
            gbc.gridy = 0;
            gbc.gridwidth = 3;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            gbc.weightx = 1.0;
            JBLabel hintLabel = new JBLabel("<html>" + I18n.t("settings.hint.githubToken") + "</html>");
            hintLabel.setForeground(javax.swing.UIManager.getColor("Component.info.foreground"));
            panel.add(hintLabel, gbc);

            // 标签
            gbc.gridx = 0;
            gbc.gridy = 1;
            gbc.gridwidth = 1;
            gbc.fill = GridBagConstraints.NONE;
            gbc.weightx = 0;
            panel.add(new JBLabel(I18n.t("settings.label.githubToken")), gbc);

            // 令牌输入框
            tokenField = new JPasswordField();
            tokenField.setText(PluginSettings.getInstance().getGithubToken());
            gbc.gridx = 1;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            gbc.weightx = 1.0;
            panel.add(tokenField, gbc);

            // 显示/隐藏按钮
            showToggle = new JToggleButton(I18n.t("settings.button.show"));
            char defaultEcho = tokenField.getEchoChar();
            showToggle.addActionListener(e -> {
                boolean selected = showToggle.isSelected();
                tokenField.setEchoChar(selected ? (char) 0 : defaultEcho);
                showToggle.setText(I18n.t(selected ? "settings.button.hide" : "settings.button.show"));
            });
            gbc.gridx = 2;
            gbc.fill = GridBagConstraints.NONE;
            gbc.weightx = 0;
            panel.add(showToggle, gbc);

            return panel;
        }

        @Override
        protected void doOKAction() {
            String token = new String(tokenField.getPassword()).trim();
            PluginSettings.getInstance().setGithubToken(token);
            Messages.showInfoMessage(
                    I18n.t("settings.githubToken.saved"),
                    I18n.t("skill.tokenConfig.title")
            );
            super.doOKAction();
        }
    }
}
