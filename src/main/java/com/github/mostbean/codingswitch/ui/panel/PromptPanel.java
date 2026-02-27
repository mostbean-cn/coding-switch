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
        toolbar.add(new JBLabel("按 CLI 筛选: "));
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
        presetList.getEmptyText().setText("暂无预设，点击 '+' 新增");

        ToolbarDecorator listDecorator = ToolbarDecorator.createDecorator(presetList)
                .setAddAction(button -> onAdd())
                .setRemoveAction(button -> onDelete())
                .addExtraAction(
                        new AnAction("启用", "将此预设写入对应 CLI 的配置文件", AllIcons.Actions.Execute) {
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
        String name = Messages.showInputDialog("预设名称:", "新增提示词预设",
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
            Messages.showWarningDialog("请先选择一个预设。", "未选择");
            return;
        }
        selected.setContent(editorArea.getText());
        PromptService.getInstance().updatePreset(selected);

        // 如果当前预设已启用，自动同步到 CLI 配置文件
        if (selected.isActive()) {
            try {
                PromptService.getInstance().activatePreset(selected.getId());
                Messages.showInfoMessage("预设内容已保存并同步到 " + selected.getTargetCli().getDisplayName() + "。", "保存成功");
            } catch (IOException ex) {
                Messages.showErrorDialog("内容已保存，但同步到配置文件失败: " + ex.getMessage(), "同步失败");
            }
        } else {
            Messages.showInfoMessage("预设内容已保存。", "保存成功");
        }
    }

    private void onDelete() {
        PromptPreset selected = presetList.getSelectedValue();
        if (selected == null)
            return;
        int result = Messages.showYesNoDialog(
                "确定删除预设 \"" + selected.getName() + "\" 吗？",
                "确认删除", Messages.getWarningIcon());
        if (result == Messages.YES) {
            PromptService.getInstance().removePreset(selected.getId());
            editorArea.setText("");
        }
    }

    private void onActivate() {
        PromptPreset selected = presetList.getSelectedValue();
        if (selected == null)
            return;
        // 先将编辑器中的内容保存到 preset，避免旧内容覆盖用户编辑
        selected.setContent(editorArea.getText());
        PromptService.getInstance().updatePreset(selected);
        try {
            PromptService.getInstance().activatePreset(selected.getId());
            Messages.showInfoMessage(
                    "预设 \"" + selected.getName() + "\" 已启用到 "
                            + selected.getTargetCli().getDisplayName() + "。",
                    "启用成功");
        } catch (IOException ex) {
            Messages.showErrorDialog("启用失败: " + ex.getMessage(), "错误");
        }
    }

    private void onLoadCurrent() {
        CliType cli = (CliType) filterCombo.getSelectedItem();
        if (cli == null)
            return;
        int result = Messages.showYesNoDialog(
                "加载当前配置将覆盖编辑器内容。\n确定继续吗？",
                "确认加载", Messages.getQuestionIcon());
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
                append("  (已启用)", new SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD,
                        new Color(66, 160, 83)));
            }

            setBorder(JBUI.Borders.empty(4, 8));
        }
    }
}
