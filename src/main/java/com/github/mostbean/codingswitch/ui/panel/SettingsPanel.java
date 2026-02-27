package com.github.mostbean.codingswitch.ui.panel;

import com.github.mostbean.codingswitch.model.CliType;
import com.github.mostbean.codingswitch.service.CliVersionService;
import com.github.mostbean.codingswitch.service.PluginSettings;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 设置面板：CLI 版本检测 + 安装命令 + 语言设置。
 */
public class SettingsPanel extends JPanel {

    private final Map<CliType, JBLabel> statusIcons = new LinkedHashMap<>();
    private final Map<CliType, JBLabel> currentLabels = new LinkedHashMap<>();
    private final Map<CliType, JBLabel> latestLabels = new LinkedHashMap<>();

    public SettingsPanel() {
        setLayout(new BorderLayout());
        setBorder(JBUI.Borders.empty(12));
        add(new JBScrollPane(buildContent()), BorderLayout.CENTER);
    }

    private JPanel buildContent() {
        JPanel mainPanel = new JPanel();
        mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));

        // ===== 版本检测区域 =====
        mainPanel.add(buildVersionSection());
        mainPanel.add(Box.createVerticalStrut(16));

        // ===== 安装命令区域 =====
        mainPanel.add(buildInstallSection());
        mainPanel.add(Box.createVerticalStrut(16));

        // ===== 偏好设置区域 =====
        mainPanel.add(buildLanguageSection());

        mainPanel.add(Box.createVerticalGlue());

        // 初始化时自动检测一次
        checkAllVersions();

        return mainPanel;
    }

    // =====================================================================
    // 版本检测区域
    // =====================================================================

    private JPanel buildVersionSection() {
        JPanel section = new JPanel(new BorderLayout(0, 8));
        section.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(), "CLI 版本状态"));

        JPanel grid = new JPanel(new GridBagLayout());
        grid.setBorder(JBUI.Borders.empty(12));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = JBUI.insets(6, 8);
        gbc.anchor = GridBagConstraints.WEST;

        // 表头
        gbc.gridy = 0;
        gbc.gridx = 0;
        grid.add(bold(new JBLabel("")), gbc);
        gbc.gridx = 1;
        grid.add(bold(new JBLabel("CLI")), gbc);
        gbc.gridx = 2;
        grid.add(bold(new JBLabel("当前版本")), gbc);
        gbc.gridx = 3;
        grid.add(bold(new JBLabel("最新版本")), gbc);

        int row = 1;
        for (CliType cli : CliType.values()) {
            JBLabel icon = new JBLabel(AllIcons.General.BalloonInformation);
            JBLabel nameLabel = new JBLabel(cli.getDisplayName());
            nameLabel.setFont(nameLabel.getFont().deriveFont(Font.BOLD));
            JBLabel curLabel = new JBLabel("检测中...");
            curLabel.setForeground(JBColor.GRAY);
            JBLabel latLabel = new JBLabel("检测中...");
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

        // 刷新按钮
        JButton refreshBtn = new JButton("检测全部版本");
        refreshBtn.setIcon(AllIcons.Actions.Refresh);
        refreshBtn.addActionListener(e -> checkAllVersions());

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        btnPanel.add(refreshBtn);

        section.add(grid, BorderLayout.CENTER);
        section.add(btnPanel, BorderLayout.SOUTH);

        return section;
    }

    // =====================================================================
    // 安装命令区域
    // =====================================================================

    private JPanel buildInstallSection() {
        JPanel section = new JPanel(new BorderLayout());
        section.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(), "安装/更新命令"));

        FormBuilder form = FormBuilder.createFormBuilder();
        for (CliType cli : CliType.values()) {
            String cmd = CliVersionService.getInstance().getInstallCommand(cli);
            form.addComponent(createCopyableCommandRow(cli.getDisplayName(), cmd));
        }

        JPanel content = form.getPanel();
        content.setBorder(JBUI.Borders.empty(8, 12));
        section.add(content, BorderLayout.CENTER);

        return section;
    }

    private JPanel createCopyableCommandRow(String cliName, String command) {
        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setBorder(JBUI.Borders.empty(4, 0));

        JBLabel label = new JBLabel(cliName + ":");
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        label.setPreferredSize(new Dimension(JBUI.scale(100), label.getPreferredSize().height));

        JTextField cmdField = new JTextField(command);
        cmdField.setEditable(false);
        cmdField.setFont(new Font(Font.MONOSPACED, Font.PLAIN, JBUI.scale(11)));
        cmdField.setBorder(JBUI.Borders.empty(4, 8));

        JButton copyBtn = new JButton(AllIcons.Actions.Copy);
        copyBtn.setToolTipText("复制到剪贴板");
        copyBtn.setPreferredSize(new Dimension(JBUI.scale(28), JBUI.scale(28)));
        copyBtn.addActionListener(e -> {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(command), null);
            copyBtn.setIcon(AllIcons.Actions.Commit);
            Timer timer = new Timer(1000, evt -> copyBtn.setIcon(AllIcons.Actions.Copy));
            timer.setRepeats(false);
            timer.start();
        });

        row.add(label, BorderLayout.WEST);
        row.add(cmdField, BorderLayout.CENTER);
        row.add(copyBtn, BorderLayout.EAST);

        return row;
    }

    // =====================================================================
    // 语言设置区域
    // =====================================================================

    private JPanel buildLanguageSection() {
        JPanel section = new JPanel(new BorderLayout());
        section.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(), "偏好设置"));

        // 语言选择行
        JPanel langRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 8));
        langRow.add(new JBLabel("界面语言:"));

        JComboBox<PluginSettings.Language> langCombo = new JComboBox<>(PluginSettings.Language.values());
        langCombo.setSelectedItem(PluginSettings.getInstance().getLanguage());
        langCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value,
                    int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof PluginSettings.Language lang) {
                    setText(lang.getDisplayName());
                }
                return this;
            }
        });
        langCombo.addActionListener(e -> onLanguageChanged(langCombo));
        langRow.add(langCombo);

        // 提示信息
        JBLabel hintLabel = new JBLabel("⚠️ 切换语言后需要重启 IDE 才能完全生效");
        hintLabel.setForeground(new JBColor(new Color(200, 130, 0), new Color(230, 180, 80)));
        hintLabel.setBorder(JBUI.Borders.empty(0, 12, 8, 12));

        section.add(langRow, BorderLayout.NORTH);
        section.add(hintLabel, BorderLayout.CENTER);

        return section;
    }

    private void onLanguageChanged(JComboBox<PluginSettings.Language> langCombo) {
        PluginSettings.Language selected = (PluginSettings.Language) langCombo.getSelectedItem();
        if (selected == null || selected == PluginSettings.getInstance().getLanguage()) {
            return;
        }

        PluginSettings.getInstance().setLanguage(selected);

        // 显示重启确认对话框
        int result = Messages.showYesNoDialog(
                "语言已切换为 " + selected.getDisplayName() + "\n\n" +
                        "需要重启 IDE 才能完全生效。\n" +
                        "是否立即重启 IDE？",
                "语言设置已更改",
                "立即重启",
                "稍后手动重启",
                Messages.getQuestionIcon());

        if (result == Messages.YES) {
            // 重启 IDE
            ApplicationManager.getApplication().restart();
        }
    }

    // =====================================================================
    // 版本检测逻辑
    // =====================================================================

    private void checkAllVersions() {
        for (CliType cli : CliType.values()) {
            currentLabels.get(cli).setText("检测中...");
            currentLabels.get(cli).setForeground(JBColor.GRAY);
            latestLabels.get(cli).setText("检测中...");
            latestLabels.get(cli).setForeground(JBColor.GRAY);
            statusIcons.get(cli).setIcon(AllIcons.General.BalloonInformation);
        }

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            CliVersionService service = CliVersionService.getInstance();
            for (CliType cli : CliType.values()) {
                String current = service.getVersion(cli);
                String latest = service.getLatestVersion(cli);
                SwingUtilities.invokeLater(() -> updateDisplay(cli, current, latest));
            }
        });
    }

    private void updateDisplay(CliType cli, String current, String latest) {
        JBLabel icon = statusIcons.get(cli);
        JBLabel curLabel = currentLabels.get(cli);
        JBLabel latLabel = latestLabels.get(cli);

        if (current != null) {
            curLabel.setText("v" + current);
            curLabel.setForeground(new Color(66, 160, 83));
            icon.setIcon(AllIcons.General.InspectionsOK);
        } else {
            curLabel.setText("未安装");
            curLabel.setForeground(JBColor.RED);
            icon.setIcon(AllIcons.General.Error);
        }

        if (latest != null) {
            if (current != null && current.equals(latest)) {
                latLabel.setText("v" + latest + " ✓ 已是最新");
                latLabel.setForeground(new Color(66, 160, 83));
            } else if (current != null) {
                latLabel.setText("v" + latest + " ⬆ 可更新");
                latLabel.setForeground(new Color(200, 130, 0));
            } else {
                latLabel.setText("v" + latest);
                latLabel.setForeground(JBColor.GRAY);
            }
        } else {
            latLabel.setText("-");
            latLabel.setForeground(JBColor.GRAY);
        }
    }

    private JBLabel bold(JBLabel label) {
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        return label;
    }
}
