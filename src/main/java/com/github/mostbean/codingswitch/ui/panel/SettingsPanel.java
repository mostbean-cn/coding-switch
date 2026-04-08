package com.github.mostbean.codingswitch.ui.panel;

import com.github.mostbean.codingswitch.model.CliType;
import com.github.mostbean.codingswitch.service.CliVersionService;
import com.github.mostbean.codingswitch.service.I18n;
import com.github.mostbean.codingswitch.service.PluginDataStorage;
import com.github.mostbean.codingswitch.service.PluginSettings;
import com.github.mostbean.codingswitch.service.PluginStorageModeService;
import com.intellij.icons.AllIcons;
import com.intellij.ide.ActivityTracker;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Action;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.table.DefaultTableModel;

/**
 * 设置面板：CLI 版本检测 + 安装命令 + 语言设置。
 */
public class SettingsPanel extends JPanel {

    private final Map<CliType, JBLabel> statusIcons = new LinkedHashMap<>();
    private final Map<CliType, JBLabel> currentLabels = new LinkedHashMap<>();
    private final Map<CliType, JBLabel> latestLabels = new LinkedHashMap<>();
    private final Map<CliType, JTextField> commandFields = new LinkedHashMap<>();

    // CLI Quick Launch UI components
    private JComboBox<String> cliQuickLaunchEnabledCombo;
    private JTable cliCommandTable;
    private DefaultTableModel cliCommandTableModel;
    private JScrollPane cliCommandTableScrollPane;
    private JPanel cliCommandTableContainer;
    private JToggleButton cliCommandTableToggle;

    public SettingsPanel() {
        setLayout(new BorderLayout());
        setBorder(JBUI.Borders.empty(12));
        add(new JBScrollPane(buildContent()), BorderLayout.CENTER);
    }

    private JPanel buildContent() {
        JPanel mainPanel = new VerticalScrollablePanel();
        mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));

        mainPanel.add(buildVersionSection());
        mainPanel.add(Box.createVerticalStrut(16));

        mainPanel.add(buildInstallSection());
        mainPanel.add(Box.createVerticalStrut(16));

        mainPanel.add(buildStorageSection());
        mainPanel.add(Box.createVerticalStrut(16));

        mainPanel.add(buildCliQuickLaunchSection());
        mainPanel.add(Box.createVerticalStrut(16));

        mainPanel.add(buildLanguageSection());
        mainPanel.add(Box.createVerticalGlue());

        checkAllVersions();
        return mainPanel;
    }

    private JPanel buildVersionSection() {
        JPanel section = new JPanel(new BorderLayout(0, 8));
        section.setBorder(
            BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(),
                I18n.t("settings.section.versionStatus")
            )
        );

        JPanel grid = new JPanel(new GridBagLayout());
        grid.setBorder(JBUI.Borders.empty(12));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = JBUI.insets(6, 8);
        gbc.anchor = GridBagConstraints.WEST;

        gbc.gridy = 0;
        gbc.gridx = 0;
        grid.add(bold(new JBLabel("")), gbc);
        gbc.gridx = 1;
        grid.add(bold(new JBLabel(I18n.t("settings.table.cli"))), gbc);
        gbc.gridx = 2;
        grid.add(
            bold(new JBLabel(I18n.t("settings.table.currentVersion"))),
            gbc
        );
        gbc.gridx = 3;
        grid.add(
            bold(new JBLabel(I18n.t("settings.table.latestVersion"))),
            gbc
        );

        int row = 1;
        for (CliType cli : CliType.values()) {
            JBLabel icon = new JBLabel(AllIcons.General.BalloonInformation);
            JBLabel nameLabel = new JBLabel(cli.getDisplayName());
            nameLabel.setFont(nameLabel.getFont().deriveFont(Font.BOLD));
            JBLabel curLabel = new JBLabel(I18n.t("settings.status.checking"));
            curLabel.setForeground(JBColor.GRAY);
            JBLabel latLabel = new JBLabel(I18n.t("settings.status.checking"));
            latLabel.setForeground(JBColor.GRAY);

            statusIcons.put(cli, icon);
            currentLabels.put(cli, curLabel);
            latestLabels.put(cli, latLabel);

            gbc.gridy = row;
            gbc.gridx = 0;
            grid.add(icon, gbc);
            gbc.gridx = 1;
            grid.add(nameLabel, gbc);
            gbc.gridx = 2;
            gbc.weightx = 0.5;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            grid.add(curLabel, gbc);
            gbc.gridx = 3;
            grid.add(latLabel, gbc);
            gbc.weightx = 0;
            gbc.fill = GridBagConstraints.NONE;

            row++;
        }

        JButton refreshBtn = new JButton(
            I18n.t("settings.button.checkAllVersions")
        );
        refreshBtn.setIcon(AllIcons.Actions.Refresh);
        refreshBtn.addActionListener(e -> checkAllVersions());

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        btnPanel.add(refreshBtn);

        section.add(grid, BorderLayout.CENTER);
        section.add(btnPanel, BorderLayout.SOUTH);
        return section;
    }

    private JPanel buildInstallSection() {
        JPanel section = new JPanel(new BorderLayout());
        section.setBorder(
            BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(),
                I18n.t("settings.section.installCommands")
            )
        );

        FormBuilder form = FormBuilder.createFormBuilder();
        for (CliType cli : CliType.values()) {
            String cmd = CliVersionService.getInstance().getInstallCommand(cli);
            form.addComponent(
                createCopyableCommandRow(cli, cli.getDisplayName(), cmd)
            );
        }

        JPanel content = form.getPanel();
        content.setBorder(JBUI.Borders.empty(8, 12));
        section.add(content, BorderLayout.CENTER);
        return section;
    }

    private JPanel createCopyableCommandRow(
        CliType cli,
        String cliName,
        String command
    ) {
        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setBorder(JBUI.Borders.empty(4, 0));

        JBLabel label = new JBLabel(cliName + ":");
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        label.setPreferredSize(
            new Dimension(JBUI.scale(100), label.getPreferredSize().height)
        );

        JTextField cmdField = new JTextField(command);
        cmdField.setEditable(false);
        cmdField.setFont(new Font(Font.MONOSPACED, Font.PLAIN, JBUI.scale(11)));
        cmdField.setBorder(JBUI.Borders.empty(4, 8));
        cmdField.setMinimumSize(
            new Dimension(0, cmdField.getMinimumSize().height)
        );
        commandFields.put(cli, cmdField);

        JButton copyBtn = new JButton(AllIcons.Actions.Copy);
        copyBtn.setToolTipText(I18n.t("settings.tooltip.copyClipboard"));
        copyBtn.setPreferredSize(new Dimension(JBUI.scale(28), JBUI.scale(28)));
        copyBtn.addActionListener(e -> {
            Toolkit.getDefaultToolkit()
                .getSystemClipboard()
                .setContents(new StringSelection(cmdField.getText()), null);
            copyBtn.setIcon(AllIcons.Actions.Commit);
            Timer timer = new Timer(1000, evt ->
                copyBtn.setIcon(AllIcons.Actions.Copy)
            );
            timer.setRepeats(false);
            timer.start();
        });

        row.add(label, BorderLayout.WEST);
        row.add(cmdField, BorderLayout.CENTER);
        row.add(copyBtn, BorderLayout.EAST);
        return row;
    }

    private JPanel buildStorageSection() {
        JPanel section = new JPanel(new BorderLayout());
        section.setBorder(
            BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(),
                I18n.t("settings.section.storageLocation")
            )
        );

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));

        JPanel storageRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 8));
        storageRow.add(createInfoHintIcon(
            I18n.t("settings.hint.dataStorageMode"),
            I18n.t("settings.label.dataStorageMode")
        ));
        storageRow.add(new JBLabel(I18n.t("settings.label.dataStorageMode")));

        JComboBox<PluginSettings.DataStorageMode> storageCombo = new JComboBox<>(
            PluginSettings.DataStorageMode.values()
        );
        storageCombo.setSelectedItem(PluginSettings.getInstance().getStorageMode());
        storageCombo.setRenderer(
            new DefaultListCellRenderer() {
                @Override
                public Component getListCellRendererComponent(
                    JList<?> list,
                    Object value,
                    int index,
                    boolean isSelected,
                    boolean cellHasFocus
                ) {
                    super.getListCellRendererComponent(
                        list,
                        value,
                        index,
                        isSelected,
                        cellHasFocus
                    );
                    if (value instanceof PluginSettings.DataStorageMode mode) {
                        setText(mode.getDisplayName(I18n.currentLanguage()));
                    }
                    return this;
                }
            }
        );
        storageRow.add(storageCombo);

        JButton applyStorageBtn = new JButton(
            I18n.t("settings.button.applyStorageMode")
        );
        applyStorageBtn.addActionListener(e -> onStorageModeChanged(storageCombo));
        storageRow.add(applyStorageBtn);

        JButton openStorageDirBtn = new JButton(
            I18n.t("settings.button.openStorageDirectory")
        );
        openStorageDirBtn.addActionListener(e ->
            openStorageDirectory(
                (PluginSettings.DataStorageMode) storageCombo.getSelectedItem()
            )
        );
        storageRow.add(openStorageDirBtn);
        content.add(storageRow);

        section.add(content, BorderLayout.NORTH);
        return section;
    }

    private JPanel buildLanguageSection() {
        JPanel section = new JPanel(new BorderLayout());
        section.setBorder(
            BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(),
                I18n.t("settings.section.preferences")
            )
        );

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));

        JPanel langRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 8));
        langRow.add(new JBLabel(I18n.t("settings.label.uiLanguage")));

        JComboBox<PluginSettings.Language> langCombo = new JComboBox<>(
            PluginSettings.Language.values()
        );
        langCombo.setSelectedItem(PluginSettings.getInstance().getLanguage());
        langCombo.setRenderer(
            new DefaultListCellRenderer() {
                @Override
                public Component getListCellRendererComponent(
                    JList<?> list,
                    Object value,
                    int index,
                    boolean isSelected,
                    boolean cellHasFocus
                ) {
                    super.getListCellRendererComponent(
                        list,
                        value,
                        index,
                        isSelected,
                        cellHasFocus
                    );
                    if (value instanceof PluginSettings.Language lang) {
                        setText(lang.getDisplayName(I18n.currentLanguage()));
                    }
                    return this;
                }
            }
        );
        langCombo.addActionListener(e -> onLanguageChanged(langCombo));
        langRow.add(langCombo);
        content.add(langRow);

        JPanel tokenRow = new JPanel(new GridBagLayout());
        tokenRow.setBorder(JBUI.Borders.empty(8, 12, 8, 12));

        GridBagConstraints tokenGbc = new GridBagConstraints();
        tokenGbc.gridy = 0;
        tokenGbc.insets = JBUI.insets(0, 0, 0, 8);
        tokenGbc.anchor = GridBagConstraints.WEST;

        tokenGbc.gridx = 0;
        tokenRow.add(createInfoHintIcon(
            I18n.t("settings.hint.githubToken"),
            I18n.t("settings.label.githubToken")
        ), tokenGbc);

        tokenGbc.gridx = 1;
        tokenRow.add(new JBLabel(I18n.t("settings.label.githubToken")), tokenGbc);

        JPasswordField tokenField = new JPasswordField();
        tokenField.setText(PluginSettings.getInstance().getGithubToken());
        tokenField.setMinimumSize(
            new Dimension(0, tokenField.getPreferredSize().height)
        );

        JToggleButton showToggle = new JToggleButton(
            I18n.t("settings.button.show")
        );
        char defaultEcho = tokenField.getEchoChar();
        showToggle.addActionListener(e -> {
            boolean selected = showToggle.isSelected();
            tokenField.setEchoChar(selected ? (char) 0 : defaultEcho);
            showToggle.setText(
                I18n.t(
                    selected ? "settings.button.hide" : "settings.button.show"
                )
            );
        });

        JButton saveTokenBtn = new JButton(
            I18n.t("settings.button.saveGithubToken")
        );
        saveTokenBtn.addActionListener(e -> {
            String token = new String(tokenField.getPassword()).trim();
            PluginSettings.getInstance().setGithubToken(token);
            Messages.showInfoMessage(
                I18n.t("settings.githubToken.saved"),
                I18n.t("settings.githubToken.title")
            );
        });

        JPanel tokenFieldPanel = new JPanel(new BorderLayout(8, 0));
        tokenFieldPanel.add(tokenField, BorderLayout.CENTER);

        JPanel tokenActionPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        tokenActionPanel.add(showToggle);
        tokenActionPanel.add(saveTokenBtn);
        tokenFieldPanel.add(tokenActionPanel, BorderLayout.EAST);

        tokenGbc.gridx = 2;
        tokenGbc.weightx = 1;
        tokenGbc.fill = GridBagConstraints.HORIZONTAL;
        tokenGbc.insets = JBUI.insets(0, 4, 0, 0);
        tokenRow.add(tokenFieldPanel, tokenGbc);
        content.add(tokenRow);

        JComponent hintLabel = createWrappedHintText(
            I18n.t("settings.hint.restartRequired"),
            new JBColor(new Color(200, 130, 0), new Color(230, 180, 80))
        );
        hintLabel.setBorder(JBUI.Borders.empty(0, 12, 8, 12));

        section.add(content, BorderLayout.NORTH);
        section.add(hintLabel, BorderLayout.CENTER);
        return section;
    }

    /**
     * CLI 快速启动配置区域：开关 + 命令列表表格 + 添加/移除按钮。
     */
    private JPanel buildCliQuickLaunchSection() {
        JPanel section = new JPanel(new BorderLayout());
        section.setBorder(
            BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(),
                I18n.t("settings.section.cliQuickLaunch")
            )
        );

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));

        // 启用开关行
        JPanel enableRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 8));
        enableRow.add(createInfoHintIcon(
            I18n.t("settings.hint.cliQuickLaunch"),
            I18n.t("settings.section.cliQuickLaunch")
        ));

        enableRow.add(new JBLabel(I18n.t("settings.label.enableCliQuickLaunch")));

        cliQuickLaunchEnabledCombo = new JComboBox<>(
            new String[] {
                I18n.t("settings.option.enabled"),
                I18n.t("settings.option.disabled")
            }
        );
        cliQuickLaunchEnabledCombo.setSelectedItem(
            PluginSettings.getInstance().isCliQuickLaunchEnabled()
                ? I18n.t("settings.option.enabled")
                : I18n.t("settings.option.disabled")
        );
        cliQuickLaunchEnabledCombo.addActionListener(e -> {
            PluginSettings.getInstance().setCliQuickLaunchEnabled(
                I18n.t("settings.option.enabled").equals(
                    cliQuickLaunchEnabledCombo.getSelectedItem()
                )
            );
            ActivityTracker.getInstance().inc();
        });
        enableRow.add(cliQuickLaunchEnabledCombo);
        content.add(enableRow);

        // 命令列表区域
        JPanel tableArea = new JPanel(new BorderLayout(0, 4));
        tableArea.setBorder(JBUI.Borders.empty(4, 20, 0, 0));

        JPanel tableHeader = new JPanel(new BorderLayout(8, 0));
        JLabel tableTitle = new JLabel(I18n.t("settings.label.cliLaunchCommands"));
        tableTitle.setFont(tableTitle.getFont().deriveFont(Font.BOLD));
        tableHeader.add(tableTitle, BorderLayout.WEST);

        cliCommandTableToggle = new JToggleButton(I18n.t("settings.button.show"));
        cliCommandTableToggle.addActionListener(e -> updateCliCommandTableCollapsedState());
        tableHeader.add(cliCommandTableToggle, BorderLayout.EAST);
        tableArea.add(tableHeader, BorderLayout.NORTH);

        // 表格：名称 | 命令
        String[] columns = {
            I18n.t("settings.table.col.name"),
            I18n.t("settings.table.col.command")
        };
        cliCommandTableModel = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) { return false; }
        };
        loadCliCommandsToTable();

        cliCommandTable = new JTable(cliCommandTableModel);
        cliCommandTable.setRowHeight(JBUI.scale(24));
        cliCommandTable.getColumnModel().getColumn(0).setPreferredWidth(JBUI.scale(120));
        cliCommandTable.getColumnModel().getColumn(1).setPreferredWidth(JBUI.scale(200));
        cliCommandTable.getTableHeader().setReorderingAllowed(false);

        cliCommandTableScrollPane = new JScrollPane(cliCommandTable);
        updateCliCommandTableViewportHeight();

        // 按钮行
        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));

        JButton addButton = new JButton(I18n.t("settings.button.addCliCommand"));
        addButton.setIcon(AllIcons.General.Add);
        addButton.addActionListener(e -> showAddOrEditDialog(-1));
        btnRow.add(addButton);

        JButton editButton = new JButton(AllIcons.Actions.Edit);
        editButton.setToolTipText("Edit");
        editButton.setPreferredSize(new Dimension(JBUI.scale(28), JBUI.scale(24)));
        editButton.addActionListener(e -> {
            int row = cliCommandTable.getSelectedRow();
            if (row >= 0) showAddOrEditDialog(row);
        });
        btnRow.add(editButton);

        JButton removeButton = new JButton(I18n.t("settings.button.removeCliCommand"));
        removeButton.setIcon(AllIcons.General.Remove);
        removeButton.addActionListener(e -> {
            int row = cliCommandTable.getSelectedRow();
            if (row >= 0) {
                cliCommandTableModel.removeRow(row);
                updateCliCommandTableViewportHeight();
            }
        });
        btnRow.add(removeButton);

        JButton saveBtn = new JButton(I18n.t("settings.button.saveCliQuickLaunch"));
        saveBtn.addActionListener(e -> saveCliQuickLaunchConfig());
        btnRow.add(saveBtn);

        btnRow.add(Box.createHorizontalGlue());

        cliCommandTableContainer = new JPanel(new BorderLayout(0, 4));
        cliCommandTableContainer.add(cliCommandTableScrollPane, BorderLayout.CENTER);
        cliCommandTableContainer.add(btnRow, BorderLayout.SOUTH);
        tableArea.add(cliCommandTableContainer, BorderLayout.CENTER);

        content.add(tableArea);
        updateCliCommandTableCollapsedState();

        section.add(content, BorderLayout.NORTH);
        return section;
    }

    private void loadCliCommandsToTable() {
        cliCommandTableModel.setRowCount(0);
        for (PluginSettings.CliQuickLaunchItem item : PluginSettings.getInstance().getCliQuickLaunchItems()) {
            cliCommandTableModel.addRow(new Object[]{item.name, item.command});
        }
        updateCliCommandTableViewportHeight();
    }

    private void showAddOrEditDialog(int editRowIndex) {
        String title = editRowIndex < 0
            ? I18n.t("settings.dialog.cliCommand.addTitle")
            : I18n.t("settings.dialog.cliCommand.editTitle");

        String defaultName = "";
        String defaultCmd = "";
        if (editRowIndex >= 0) {
            defaultName = String.valueOf(cliCommandTableModel.getValueAt(editRowIndex, 0));
            defaultCmd = String.valueOf(cliCommandTableModel.getValueAt(editRowIndex, 1));
        }

        JTextField nameField = new JTextField(defaultName, 16);
        JTextField cmdField = new JTextField(defaultCmd, 30);

        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(JBUI.Borders.empty(12));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = JBUI.insets(4, 8);
        gbc.anchor = GridBagConstraints.WEST;

        gbc.gridy = 0;
        gbc.gridx = 0;
        panel.add(new JLabel(I18n.t("settings.dialog.cliCommand.name")), gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        panel.add(nameField, gbc);

        gbc.gridy = 1;
        gbc.gridx = 0;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        panel.add(new JLabel(I18n.t("settings.dialog.cliCommand.command")), gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        panel.add(cmdField, gbc);

        int result = JOptionPane.showConfirmDialog(
            this,
            panel,
            title,
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE
        );

        if (result == JOptionPane.OK_OPTION) {
            String name = nameField.getText().trim();
            String command = cmdField.getText().trim();
            if (name.isEmpty() || command.isEmpty()) {
                return;
            }
            if (editRowIndex >= 0) {
                cliCommandTableModel.setValueAt(name, editRowIndex, 0);
                cliCommandTableModel.setValueAt(command, editRowIndex, 1);
            } else {
                cliCommandTableModel.addRow(new Object[]{name, command});
                updateCliCommandTableViewportHeight();
            }
        }
    }

    private void saveCliQuickLaunchConfig() {
        boolean enabled = I18n.t("settings.option.enabled").equals(
            cliQuickLaunchEnabledCombo.getSelectedItem()
        );

        java.util.List<PluginSettings.CliQuickLaunchItem> items = new java.util.ArrayList<>();
        for (int i = 0; i < cliCommandTableModel.getRowCount(); i++) {
            String name = String.valueOf(cliCommandTableModel.getValueAt(i, 0)).trim();
            String command = String.valueOf(cliCommandTableModel.getValueAt(i, 1)).trim();
            if (!name.isEmpty() && !command.isEmpty()) {
                items.add(new PluginSettings.CliQuickLaunchItem(name, command));
            }
        }

        PluginSettings settings = PluginSettings.getInstance();
        settings.setCliQuickLaunchEnabled(enabled);
        settings.setCliQuickLaunchItems(items);

        // 当前选择为空或已经失效时，自动回退到第一项；列表为空则清空选择。
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
    }

    private void updateCliCommandTableViewportHeight() {
        if (cliCommandTable == null || cliCommandTableScrollPane == null) {
            return;
        }

        int visibleRows = Math.max(1, Math.min(cliCommandTable.getRowCount(), 4));
        int headerHeight = cliCommandTable.getTableHeader() == null
            ? 0
            : cliCommandTable.getTableHeader().getPreferredSize().height;
        int height = headerHeight + cliCommandTable.getRowHeight() * visibleRows + JBUI.scale(2);

        cliCommandTableScrollPane.setPreferredSize(new Dimension(0, height));
        cliCommandTableScrollPane.revalidate();
    }

    private void updateCliCommandTableCollapsedState() {
        if (cliCommandTableContainer == null || cliCommandTableToggle == null) {
            return;
        }

        boolean expanded = cliCommandTableToggle.isSelected();
        cliCommandTableContainer.setVisible(expanded);
        cliCommandTableToggle.setText(
            I18n.t(expanded ? "settings.button.hide" : "settings.button.show")
        );
        revalidate();
        repaint();
    }

    private JPanel createWrappedHintRow(String text) {
        JPanel row = new JPanel(new BorderLayout());
        row.setBorder(JBUI.Borders.empty(0, 12, 8, 12));
        row.add(createWrappedHintText(text, JBColor.GRAY), BorderLayout.CENTER);
        return row;
    }

    private JComponent createWrappedHintText(String text, Color color) {
        JTextArea area = new JTextArea(text);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setOpaque(false);
        area.setFocusable(false);
        area.setForeground(color);
        area.setBorder(JBUI.Borders.empty());
        return area;
    }

    private JComponent createInfoHintIcon(String text, String title) {
        JBLabel iconLabel = new JBLabel(AllIcons.General.ContextHelp);
        iconLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        iconLabel.setToolTipText(toHtmlTooltip(text));
        iconLabel.addMouseListener(
            new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    Messages.showInfoMessage(text, title);
                }
            }
        );
        return iconLabel;
    }

    private String toHtmlTooltip(String text) {
        String escaped = text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\n", "<br>");
        return "<html><body style='width: 260px;'>" + escaped + "</body></html>";
    }

    private void onLanguageChanged(
        JComboBox<PluginSettings.Language> langCombo
    ) {
        PluginSettings.Language selected =
            (PluginSettings.Language) langCombo.getSelectedItem();
        if (
            selected == null ||
            selected == PluginSettings.getInstance().getLanguage()
        ) {
            return;
        }

        PluginSettings.getInstance().setLanguage(selected);

        int result = Messages.showYesNoDialog(
            I18n.t(
                "settings.dialog.languageChanged.message",
                selected.getDisplayName(I18n.currentLanguage())
            ),
            I18n.t("settings.dialog.languageChanged.title"),
            I18n.t("settings.dialog.languageChanged.restartNow"),
            I18n.t("settings.dialog.languageChanged.restartLater"),
            Messages.getQuestionIcon()
        );

        if (result == Messages.YES) {
            ApplicationManager.getApplication().restart();
        }
    }

    private void onStorageModeChanged(
        JComboBox<PluginSettings.DataStorageMode> storageCombo
    ) {
        PluginSettings.DataStorageMode current =
            PluginSettings.getInstance().getStorageMode();
        PluginSettings.DataStorageMode selected =
            (PluginSettings.DataStorageMode) storageCombo.getSelectedItem();
        if (selected == null || selected == current) {
            return;
        }

        PluginStorageModeService.SharedDataStrategy strategy = null;
        if (selected == PluginSettings.DataStorageMode.USER_SHARED) {
            PluginStorageModeService.UserSharedInspection inspection =
                PluginStorageModeService.getInstance().inspectUserSharedState();
            if (inspection.hasSharedData()) {
                StorageConflictDialog dialog = new StorageConflictDialog(
                    inspection
                );
                dialog.show();
                strategy = dialog.getSelectedStrategy();
                if (strategy == null) {
                    storageCombo.setSelectedItem(current);
                    return;
                }

                int detailedConfirm = Messages.showYesNoDialog(
                    buildStorageOverwriteConfirmMessage(strategy, inspection),
                    I18n.t("settings.dialog.storageMode.confirmTitle"),
                    I18n.t("settings.dialog.storageMode.confirmProceed"),
                    I18n.t("settings.dialog.storageMode.confirmNo"),
                    Messages.getQuestionIcon()
                );
                if (detailedConfirm != Messages.YES) {
                    storageCombo.setSelectedItem(current);
                    return;
                }
            } else {
                int confirm = Messages.showYesNoDialog(
                    I18n.t(
                        "settings.dialog.storageMode.confirm",
                        selected.getDisplayName(I18n.currentLanguage())
                    ),
                    I18n.t("settings.dialog.storageMode.confirmTitle"),
                    I18n.t("settings.dialog.storageMode.confirmYes"),
                    I18n.t("settings.dialog.storageMode.confirmNo"),
                    Messages.getQuestionIcon()
                );
                if (confirm != Messages.YES) {
                    storageCombo.setSelectedItem(current);
                    return;
                }
            }
        } else {
            int confirm = Messages.showYesNoDialog(
                I18n.t(
                    "settings.dialog.storageMode.confirmBackToLocal",
                    selected.getDisplayName(I18n.currentLanguage())
                ),
                I18n.t("settings.dialog.storageMode.confirmTitle"),
                I18n.t("settings.dialog.storageMode.confirmYes"),
                I18n.t("settings.dialog.storageMode.confirmNo"),
                Messages.getQuestionIcon()
            );
            if (confirm != Messages.YES) {
                storageCombo.setSelectedItem(current);
                return;
            }
        }

        PluginStorageModeService.SwitchResult result =
            PluginStorageModeService.getInstance().switchMode(selected, strategy);
        if (!result.success()) {
            storageCombo.setSelectedItem(current);
            Messages.showErrorDialog(
                I18n.t("settings.dialog.storageMode.failed"),
                I18n.t("settings.dialog.storageMode.confirmTitle")
            );
            return;
        }
        Messages.showInfoMessage(
            I18n.t(
                "settings.dialog.storageMode.applied",
                selected.getDisplayName(I18n.currentLanguage())
            ),
            I18n.t("settings.dialog.storageMode.appliedTitle")
        );
    }

    private void openStorageDirectory(PluginSettings.DataStorageMode mode) {
        PluginSettings.DataStorageMode targetMode =
            mode != null ? mode : PluginSettings.getInstance().getStorageMode();
        Path directory = resolveStorageDirectory(targetMode);

        try {
            Files.createDirectories(directory);
            if (!Desktop.isDesktopSupported()) {
                throw new IOException("desktop-not-supported");
            }
            Desktop.getDesktop().open(directory.toFile());
        } catch (Exception ex) {
            Messages.showErrorDialog(
                I18n.t(
                    "settings.dialog.storageDirectory.openFailed",
                    directory.toString()
                ),
                I18n.t("settings.section.storageLocation")
            );
        }
    }

    private Path resolveStorageDirectory(PluginSettings.DataStorageMode mode) {
        return mode == PluginSettings.DataStorageMode.USER_SHARED
            ? PluginDataStorage.getUserSharedRootDir()
            : Path.of(PathManager.getOptionsPath());
    }

    private String buildStorageOverwriteConfirmMessage(
        PluginStorageModeService.SharedDataStrategy strategy,
        PluginStorageModeService.UserSharedInspection inspection
    ) {
        return switch (strategy) {
            case LOCAL_TO_SHARED -> I18n.t(
                "settings.dialog.storageMode.confirmLocalToShared",
                inspection.localSummary().totalCount(),
                inspection.localSummary().providerCount(),
                inspection.localSummary().promptCount(),
                inspection.localSummary().skillCount(),
                inspection.localSummary().mcpCount()
            );
            case SHARED_TO_LOCAL -> I18n.t(
                "settings.dialog.storageMode.confirmSharedToLocal",
                inspection.sharedSummary().totalCount(),
                inspection.sharedSummary().providerCount(),
                inspection.sharedSummary().promptCount(),
                inspection.sharedSummary().skillCount(),
                inspection.sharedSummary().mcpCount()
            );
        };
    }

    private void checkAllVersions() {
        for (CliType cli : CliType.values()) {
            currentLabels.get(cli).setText(I18n.t("settings.status.checking"));
            currentLabels.get(cli).setForeground(JBColor.GRAY);
            latestLabels.get(cli).setText(I18n.t("settings.status.checking"));
            latestLabels.get(cli).setForeground(JBColor.GRAY);
            statusIcons.get(cli).setIcon(AllIcons.General.BalloonInformation);
        }

        CliVersionService service = CliVersionService.getInstance();
        for (CliType cli : CliType.values()) {
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                CliVersionService.VersionResult current =
                    service.getVersionResult(cli);
                String latest =
                    current.status() == CliVersionService.VersionStatus.INSTALLED
                        ? service.getLatestVersion(cli, current.version())
                        : null;
                SwingUtilities.invokeLater(() ->
                    updateDisplay(cli, current, latest)
                );
            });
        }
    }

    private void updateDisplay(
        CliType cli,
        CliVersionService.VersionResult currentResult,
        String latest
    ) {
        JBLabel icon = statusIcons.get(cli);
        JBLabel curLabel = currentLabels.get(cli);
        JBLabel latLabel = latestLabels.get(cli);

        String current = currentResult != null ? currentResult.version() : null;
        CliVersionService.VersionStatus status =
            currentResult != null
                ? currentResult.status()
                : CliVersionService.VersionStatus.NOT_INSTALLED;
        updateRecommendedCommand(cli, status);

        switch (status) {
            case INSTALLED -> {
                curLabel.setText("v" + current);
                curLabel.setForeground(new Color(66, 160, 83));
                icon.setIcon(AllIcons.General.InspectionsOK);
            }
            case TIMEOUT -> {
                curLabel.setText(I18n.t("settings.status.detectTimeout"));
                curLabel.setForeground(new Color(200, 130, 0));
                icon.setIcon(AllIcons.General.BalloonWarning);
            }
            case COMMAND_FAILED -> {
                curLabel.setText(I18n.t("settings.status.detectFailed"));
                curLabel.setForeground(new Color(200, 130, 0));
                icon.setIcon(AllIcons.General.BalloonWarning);
            }
            case NOT_INSTALLED -> {
                curLabel.setText(I18n.t("settings.status.notInstalled"));
                curLabel.setForeground(JBColor.RED);
                icon.setIcon(AllIcons.General.Error);
            }
        }

        if (latest != null) {
            int versionComparison = CliVersionService.compareVersions(
                current,
                latest
            );
            if (
                status == CliVersionService.VersionStatus.INSTALLED &&
                current != null &&
                versionComparison == 0
            ) {
                latLabel.setText(I18n.t("settings.status.latest", latest));
                latLabel.setForeground(new Color(66, 160, 83));
            } else if (
                status == CliVersionService.VersionStatus.INSTALLED &&
                current != null &&
                versionComparison < 0
            ) {
                latLabel.setText(I18n.t("settings.status.updatable", latest));
                latLabel.setForeground(new Color(200, 130, 0));
            } else if (
                status == CliVersionService.VersionStatus.INSTALLED &&
                current != null
            ) {
                latLabel.setText(I18n.t("settings.status.localNewer", latest));
                latLabel.setForeground(new Color(66, 139, 202));
            } else {
                latLabel.setText("v" + latest);
                latLabel.setForeground(JBColor.GRAY);
            }
        } else {
            latLabel.setText("-");
            latLabel.setForeground(JBColor.GRAY);
        }
    }

    private void updateRecommendedCommand(
        CliType cli,
        CliVersionService.VersionStatus status
    ) {
        JTextField commandField = commandFields.get(cli);
        if (commandField == null) {
            return;
        }
        String command = CliVersionService.getInstance()
            .getRecommendedCommand(cli, status);
        commandField.setText(command);
        commandField.setCaretPosition(0);
    }

    private JBLabel bold(JBLabel label) {
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        return label;
    }

    private static final class StorageConflictDialog extends DialogWrapper {

        private final PluginStorageModeService.UserSharedInspection inspection;
        private PluginStorageModeService.SharedDataStrategy selectedStrategy;
        private JComponent preferredFocus;

        private StorageConflictDialog(
            PluginStorageModeService.UserSharedInspection inspection
        ) {
            super(true);
            this.inspection = inspection;
            setTitle(I18n.t("settings.dialog.storageMode.detectTitle"));
            setResizable(false);
            init();
        }

        @Override
        protected JComponent createCenterPanel() {
            JPanel panel = new JPanel(new BorderLayout(0, 12));
            panel.setBorder(JBUI.Borders.empty(8, 4));

            JTextArea textArea = new JTextArea(
                I18n.t(
                    "settings.dialog.storageMode.conflict",
                    inspection.localSummary().totalCount(),
                    inspection.sharedSummary().totalCount(),
                    inspection.localSummary().providerCount(),
                    inspection.localSummary().promptCount(),
                    inspection.localSummary().skillCount(),
                    inspection.localSummary().mcpCount(),
                    inspection.sharedSummary().providerCount(),
                    inspection.sharedSummary().promptCount(),
                    inspection.sharedSummary().skillCount(),
                    inspection.sharedSummary().mcpCount()
                )
            );
            textArea.setEditable(false);
            textArea.setLineWrap(true);
            textArea.setWrapStyleWord(true);
            textArea.setOpaque(false);
            textArea.setBorder(JBUI.Borders.empty());
            textArea.setFocusable(false);

            JBScrollPane scrollPane = new JBScrollPane(textArea);
            scrollPane.setBorder(JBUI.Borders.empty());
            scrollPane.setPreferredSize(new Dimension(JBUI.scale(420), JBUI.scale(170)));
            preferredFocus = scrollPane;
            panel.add(scrollPane, BorderLayout.CENTER);
            return panel;
        }

        @Override
        protected JComponent createSouthPanel() {
            JPanel panel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
            panel.setBorder(JBUI.Borders.empty(8, 0, 0, 0));

            panel.add(createChoiceButton(
                I18n.t("settings.dialog.storageMode.option.localToShared"),
                PluginStorageModeService.SharedDataStrategy.LOCAL_TO_SHARED
            ));
            panel.add(createChoiceButton(
                I18n.t("settings.dialog.storageMode.option.sharedToLocal"),
                PluginStorageModeService.SharedDataStrategy.SHARED_TO_LOCAL
            ));

            JButton cancelButton = new JButton(
                I18n.t("settings.dialog.storageMode.confirmNo")
            );
            cancelButton.setDefaultCapable(false);
            cancelButton.addActionListener(e -> doCancelAction());
            panel.add(cancelButton);
            return panel;
        }

        private JButton createChoiceButton(
            String text,
            PluginStorageModeService.SharedDataStrategy strategy
        ) {
            JButton button = new JButton(text);
            button.setDefaultCapable(false);
            button.addActionListener(e -> {
                selectedStrategy = strategy;
                close(OK_EXIT_CODE);
            });
            return button;
        }

        @Override
        public JComponent getPreferredFocusedComponent() {
            return preferredFocus;
        }

        @Override
        protected Action[] createActions() {
            return new Action[0];
        }

        private PluginStorageModeService.SharedDataStrategy getSelectedStrategy() {
            return selectedStrategy;
        }
    }

    /**
     * 实现 Scrollable 接口的面板，声明跟随视口宽度。
     * 这样 JScrollPane 始终将内容宽度约束为视口宽度，
     * 不会出现水平滚动条，BorderLayout 中的 EAST（复制按钮）永远固定在右侧可见。
     */
    private static class VerticalScrollablePanel
        extends JPanel
        implements Scrollable
    {

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(
            Rectangle visibleRect,
            int orientation,
            int direction
        ) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(
            Rectangle visibleRect,
            int orientation,
            int direction
        ) {
            return visibleRect.height;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true; // 强制内容宽度 = 视口宽度，文本框收缩，按钮始终在右侧
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }
}
