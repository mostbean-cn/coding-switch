package com.github.mostbean.codingswitch.ui.panel;

import com.github.mostbean.codingswitch.model.CliType;
import com.github.mostbean.codingswitch.service.CliVersionService;
import com.github.mostbean.codingswitch.service.I18n;
import com.github.mostbean.codingswitch.service.PluginSettings;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * 设置面板：CLI 版本检测 + 安装命令 + 语言设置。
 */
public class SettingsPanel extends JPanel {

    private final Map<CliType, JBLabel> statusIcons = new LinkedHashMap<>();
    private final Map<CliType, JBLabel> currentLabels = new LinkedHashMap<>();
    private final Map<CliType, JBLabel> latestLabels = new LinkedHashMap<>();
    private final Map<CliType, JTextField> commandFields = new LinkedHashMap<>();

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

        JPanel tokenRow = new JPanel(new BorderLayout(12, 0));
        tokenRow.setBorder(JBUI.Borders.empty(8, 12, 8, 12));

        JBLabel tokenLabel = new JBLabel(I18n.t("settings.label.githubToken"));
        tokenLabel.setPreferredSize(
            new Dimension(JBUI.scale(90), tokenLabel.getPreferredSize().height)
        );
        tokenRow.add(tokenLabel, BorderLayout.WEST);

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

        tokenRow.add(tokenFieldPanel, BorderLayout.CENTER);
        content.add(tokenRow);

        JPanel tokenHintRow = new JPanel(
            new FlowLayout(FlowLayout.LEFT, 12, 0)
        );
        JBLabel tokenHintLabel = new JBLabel(
            I18n.t("settings.hint.githubToken")
        );
        tokenHintLabel.setForeground(JBColor.GRAY);
        tokenHintRow.add(tokenHintLabel);
        content.add(tokenHintRow);

        JBLabel hintLabel = new JBLabel(
            I18n.t("settings.hint.restartRequired")
        );
        hintLabel.setForeground(
            new JBColor(new Color(200, 130, 0), new Color(230, 180, 80))
        );
        hintLabel.setBorder(JBUI.Borders.empty(0, 12, 8, 12));

        section.add(content, BorderLayout.NORTH);
        section.add(hintLabel, BorderLayout.CENTER);
        return section;
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
