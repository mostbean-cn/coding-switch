package com.github.mostbean.codingswitch.ui.panel;

import com.github.mostbean.codingswitch.model.CliType;
import com.github.mostbean.codingswitch.model.PromptPreset;
import com.github.mostbean.codingswitch.service.PromptService;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.ColoredListCellRenderer;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;

/**
 * 提示词预设管理面板。
 * 左侧是预设列表（ToolbarDecorator），右侧是 Markdown 编辑区，中间用 JBSplitter 分割。
 */
public class PromptPanel extends JPanel {

    private final DefaultListModel<PromptPreset> listModel = new DefaultListModel<>();
    private final JBList<PromptPreset> presetList = new JBList<>(listModel);
    private final JBTextArea editorArea = new JBTextArea();
    private final JComboBox<CliType> filterCombo = new JComboBox<>();

    public PromptPanel() {
        setLayout(new BorderLayout());
        setBorder(JBUI.Borders.empty(8));

        add(createToolbar(), BorderLayout.NORTH);
        add(createSplitPane(), BorderLayout.CENTER);

        PromptService.getInstance().addChangeListener(this::refreshList);
        presetList.addListSelectionListener(e -> onPresetSelected());
        refreshList();
    }

    private JPanel createToolbar() {
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        toolbar.add(new JBLabel("Filter by CLI: "));
        for (CliType cli : CliType.values()) {
            filterCombo.addItem(cli);
        }
        filterCombo.addActionListener(e -> {
            refreshList();
            editorArea.setText("");
        });
        toolbar.add(filterCombo);
        return toolbar;
    }

    private JComponent createSplitPane() {
        // 左侧：预设列表 (配合 ToolbarDecorator)
        presetList.setCellRenderer(new PresetCellRenderer());
        presetList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        presetList.getEmptyText().setText("No presets for this CLI");

        ToolbarDecorator listDecorator = ToolbarDecorator.createDecorator(presetList)
                .setAddAction(button -> onAdd())
                .setRemoveAction(button -> onDelete())
                .addExtraAction(
                        new AnAction("Activate", "Activate this preset to live config", AllIcons.Actions.Execute) {
                            @Override
                            public void actionPerformed(@NotNull AnActionEvent e) {
                                onActivate();
                            }

                            @Override
                            public void update(@NotNull AnActionEvent e) {
                                e.getPresentation().setEnabled(presetList.getSelectedValue() != null);
                            }
                        });
        listDecorator.disableUpDownActions();

        JPanel leftPanel = listDecorator.createPanel();
        leftPanel.setMinimumSize(new Dimension(200, 0));

        // 右侧：Markdown 编辑器区域
        editorArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, JBUI.scale(13)));
        editorArea.setLineWrap(true);
        editorArea.setWrapStyleWord(true);
        editorArea.setMargin(JBUI.insets(8));

        JBScrollPane editorScroll = new JBScrollPane(editorArea);
        editorScroll.setBorder(JBUI.Borders.empty());

        // 右侧工具栏（Save, Load Current）
        JPanel editorActionPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 4));
        JButton loadBtn = new JButton("加载当前配置");
        loadBtn.addActionListener(e -> onLoadCurrent());
        editorActionPanel.add(loadBtn);

        JButton saveBtn = new JButton("保存内容");
        saveBtn.addActionListener(e -> onSave());
        saveBtn.putClientProperty("JButton.buttonType", "default");
        editorActionPanel.add(saveBtn);

        JPanel rightPanel = new JPanel(new BorderLayout());
        rightPanel.setBorder(JBUI.Borders.customLine(UIManager.getColor("Borders.color"), 1));
        rightPanel.add(editorScroll, BorderLayout.CENTER);
        rightPanel.add(editorActionPanel, BorderLayout.SOUTH);

        // 使用原生的 JBSplitter
        JBSplitter splitter = new JBSplitter(false, 0.3f);
        splitter.setFirstComponent(leftPanel);
        splitter.setSecondComponent(rightPanel);
        splitter.setDividerWidth(JBUI.scale(4));

        return splitter;
    }

    // =====================================================================
    // 操作方法
    // =====================================================================

    private void onPresetSelected() {
        PromptPreset selected = presetList.getSelectedValue();
        if (selected != null) {
            editorArea.setText(selected.getContent() != null ? selected.getContent() : "");
            editorArea.setCaretPosition(0);
        }
    }

    private void onAdd() {
        String name = Messages.showInputDialog("Preset name:", "New Prompt Preset",
                Messages.getQuestionIcon());
        if (name != null && !name.isBlank()) {
            CliType cli = (CliType) filterCombo.getSelectedItem();
            PromptPreset preset = new PromptPreset(name.trim(), "", cli);
            PromptService.getInstance().addPreset(preset);
            presetList.setSelectedValue(preset, true);
        }
    }

    private void onSave() {
        PromptPreset selected = presetList.getSelectedValue();
        if (selected == null) {
            Messages.showWarningDialog("Please select a preset first.", "No Selection");
            return;
        }
        selected.setContent(editorArea.getText());
        PromptService.getInstance().updatePreset(selected);
        Messages.showInfoMessage("Preset content saved.", "Success");
    }

    private void onDelete() {
        PromptPreset selected = presetList.getSelectedValue();
        if (selected == null)
            return;
        int result = Messages.showYesNoDialog(
                "Delete preset \"" + selected.getName() + "\"?",
                "Confirm Delete", Messages.getWarningIcon());
        if (result == Messages.YES) {
            PromptService.getInstance().removePreset(selected.getId());
            editorArea.setText("");
        }
    }

    private void onActivate() {
        PromptPreset selected = presetList.getSelectedValue();
        if (selected == null)
            return;
        try {
            PromptService.getInstance().activatePreset(selected.getId());
            Messages.showInfoMessage(
                    "Preset \"" + selected.getName() + "\" activated for "
                            + selected.getTargetCli().getDisplayName() + ".",
                    "Activated");
        } catch (IOException ex) {
            Messages.showErrorDialog("Failed to activate: " + ex.getMessage(), "Error");
        }
    }

    private void onLoadCurrent() {
        CliType cli = (CliType) filterCombo.getSelectedItem();
        if (cli == null)
            return;
        int result = Messages.showYesNoDialog(
                "Loading live config will overwrite editor content.\nContinue?",
                "Confirm Load", Messages.getQuestionIcon());
        if (result == Messages.YES) {
            String content = PromptService.getInstance().readCurrentPrompt(cli);
            editorArea.setText(content);
            editorArea.setCaretPosition(0);
        }
    }

    private void refreshList() {
        PromptPreset selected = presetList.getSelectedValue();
        listModel.clear();
        CliType filter = (CliType) filterCombo.getSelectedItem();
        if (filter != null) {
            for (PromptPreset p : PromptService.getInstance().getPresetsByType(filter)) {
                listModel.addElement(p);
            }
        }
        if (selected != null) {
            presetList.setSelectedValue(selected, true);
        }
    }

    // =====================================================================
    // 渲染器：使用 ColoredListCellRenderer 支持 append()
    // =====================================================================

    private static class PresetCellRenderer extends ColoredListCellRenderer<PromptPreset> {
        @Override
        protected void customizeCellRenderer(@NotNull JList<? extends PromptPreset> list, PromptPreset preset,
                int index, boolean selected, boolean hasFocus) {
            if (preset.isActive()) {
                setIcon(AllIcons.Actions.Commit);
            } else {
                setIcon(AllIcons.FileTypes.Text);
            }

            append(preset.getName());

            if (preset.isActive()) {
                append("  (Active)", new SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD,
                        new Color(66, 160, 83)));
            }

            setBorder(JBUI.Borders.empty(4, 8));
        }
    }
}
