package com.github.mostbean.codingswitch.ui.dialog;

import com.github.mostbean.codingswitch.service.I18n;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

/**
 * CLI 命令配置对话框
 */
public class CliCommandDialog extends DialogWrapper {

    private final JTextField nameField;
    private final JTextField commandField;
    private final boolean isEditMode;

    public CliCommandDialog(Component parent, String defaultName, String defaultCommand) {
        super(parent, true);
        this.isEditMode = defaultName != null && !defaultName.isEmpty();

        setTitle(isEditMode
            ? I18n.t("settings.dialog.cliCommand.editTitle")
            : I18n.t("settings.dialog.cliCommand.addTitle"));

        nameField = new JTextField(defaultName != null ? defaultName : "", 20);
        commandField = new JTextField(defaultCommand != null ? defaultCommand : "", 35);

        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel mainPanel = new JPanel(new BorderLayout(0, 12));
        mainPanel.setBorder(JBUI.Borders.empty(8));

        // 表单区域
        JPanel formPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = JBUI.insets(6, 8);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        // 名称字段
        gbc.gridy = 0;
        gbc.gridx = 0;
        gbc.weightx = 0;
        formPanel.add(new JLabel(I18n.t("settings.dialog.cliCommand.name")), gbc);

        gbc.gridx = 1;
        gbc.weightx = 1.0;
        formPanel.add(nameField, gbc);

        // 命令字段
        gbc.gridy = 1;
        gbc.gridx = 0;
        gbc.weightx = 0;
        formPanel.add(new JLabel(I18n.t("settings.dialog.cliCommand.command")), gbc);

        gbc.gridx = 1;
        gbc.weightx = 1.0;
        formPanel.add(commandField, gbc);

        mainPanel.add(formPanel, BorderLayout.CENTER);

        // 预设按钮区域
        JPanel presetsPanel = createPresetsPanel();
        mainPanel.add(presetsPanel, BorderLayout.SOUTH);

        return mainPanel;
    }

    private JPanel createPresetsPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.setBorder(JBUI.Borders.empty(8, 0, 0, 0));

        JLabel label = new JLabel(I18n.t("settings.dialog.cliCommand.presets"));
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        panel.add(label, BorderLayout.NORTH);

        JPanel buttonsPanel = new JPanel(new GridLayout(2, 2, 8, 8));

        addPresetButton(buttonsPanel, "Claude Code", "claude --dangerously-skip-permissions");
        addPresetButton(buttonsPanel, "Codex", "codex");
        addPresetButton(buttonsPanel, "OpenCode", "opencode");
        addPresetButton(buttonsPanel, "Antigravity CLI", "agy --dangerously-skip-permissions");

        panel.add(buttonsPanel, BorderLayout.CENTER);

        return panel;
    }

    private void addPresetButton(JPanel parent, String name, String command) {
        JButton button = new JButton(name);
        button.addActionListener(e -> {
            nameField.setText(name);
            commandField.setText(command);
        });
        parent.add(button);
    }

    @Override
    protected void doOKAction() {
        String name = nameField.getText().trim();
        String command = commandField.getText().trim();

        if (name.isEmpty() || command.isEmpty()) {
            return;
        }

        super.doOKAction();
    }

    public String getCliName() {
        return nameField.getText().trim();
    }

    public String getCliCommand() {
        return commandField.getText().trim();
    }

    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return nameField;
    }
}
