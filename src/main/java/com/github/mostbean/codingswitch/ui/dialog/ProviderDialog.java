package com.github.mostbean.codingswitch.ui.dialog;

import com.github.mostbean.codingswitch.model.CliType;
import com.github.mostbean.codingswitch.model.Provider;
import com.github.mostbean.codingswitch.service.I18n;
import com.github.mostbean.codingswitch.service.ProviderConnectionTestService;
import com.github.mostbean.codingswitch.service.ProviderPresets;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Provider 编辑对话框。
 * 顶部预设平铺按钮，选择后自动填充下方字段。
 */
public class ProviderDialog extends DialogWrapper {

    private static final String PRESET_NONE = I18n.t("providerDialog.preset.custom");
    private static final String OFFICIAL_HINT = I18n.t("providerDialog.preset.officialHint");

    private final JPanel presetButtonsPanel = new JPanel(new GridLayout(0, 5, 8, 8));
    private final JBLabel presetHintLabel = new JBLabel(" ");

    private final JComboBox<CliType> cliTypeCombo = new JComboBox<>(CliType.values());
    private final JTextField nameField = new JTextField(30);

    private final JTextField claudeApiKey = new JTextField(30);
    private final JTextField claudeBaseUrl = new JTextField(30);
    private final JTextField claudeModel = new JTextField(30);
    private final JTextField claudeHaiku = new JTextField(30);
    private final JTextField claudeSonnet = new JTextField(30);
    private final JTextField claudeOpus = new JTextField(30);
    private final JComboBox<String> claudeApiKeyField = new JComboBox<>(
            new String[] { "ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_API_KEY" });
    private final JComboBox<String> claudeEffortLevel = createEditableCombo(
            "", "high", "medium", "low");
    private final JComboBox<String> claudeAlwaysThinkingEnabled = new JComboBox<>(
            new String[] { "", "true", "false" });

    private final JTextField codexApiKey = new JTextField(30);
    private final JTextField codexBaseUrl = new JTextField(30);
    private final JTextField codexModel = new JTextField(30);
    private final JComboBox<String> codexReasoningEffort = new JComboBox<>(
            new String[] { "xhigh", "high", "medium", "low" });
    private static final String CODEX_PROVIDER_SLUG = "custom";

    private final JTextField geminiApiKey = new JTextField(30);
    private final JTextField geminiBaseUrl = new JTextField(30);
    private final JTextField geminiModel = new JTextField(30);

    private final JTextField opencodeApiKey = new JTextField(30);
    private final JTextField opencodeBaseUrl = new JTextField(30);
    private final JPanel opencodeModelsPanel = new JPanel();
    private final List<ModelRow> opencodeModelRows = new ArrayList<>();
    private final JComboBox<String> opencodeNpm = createEditableCombo(
            "@ai-sdk/openai-compatible",
            "@ai-sdk/openai",
            "@ai-sdk/anthropic",
            "@ai-sdk/google",
            "@ai-sdk/google-vertex",
            "@ai-sdk/azure",
            "@ai-sdk/amazon-bedrock",
            "@ai-sdk/mistral");

    private static class ModelRow {
        final JTextField nameField;
        final JComboBox<String> reasoningEffortCombo;
        final JPanel panel;

        ModelRow(JTextField nameField, JComboBox<String> reasoningEffortCombo, JPanel panel) {
            this.nameField = nameField;
            this.reasoningEffortCombo = reasoningEffortCombo;
            this.panel = panel;
        }
    }

    private final JPanel dynamicPanel = new JPanel(new CardLayout());
    private final JButton testConnectionButton = new JButton(I18n.t("providerDialog.button.testConnection"));
    private final JBLabel testConnectionHintLabel = new JBLabel(I18n.t("providerDialog.test.hint"));
    private final JBLabel testStatusLabel = new JBLabel(" ");
    private final Provider provider;
    private String selectedPreset = PRESET_NONE;
    private List<ProviderPresets.Preset> currentPresets = List.of();

    private static final Gson PREVIEW_GSON = new GsonBuilder().setPrettyPrinting().create();
    private final JTextArea previewTextArea = createPreviewTextArea(true);
    private final JTabbedPane codexPreviewTabs = new JTabbedPane();
    private final JTextArea codexAuthPreview = createPreviewTextArea(true);
    private final JTextArea codexTomlPreview = createPreviewTextArea(true);
    private final JPanel previewPanel = new JPanel(new BorderLayout());
    private final JButton togglePreviewButton = new JButton(I18n.t("providerDialog.button.showPreview"));
    private boolean previewVisible = false;
    private boolean updatingFromPreview = false;
    private JSplitPane splitPane;
    private JPanel leftPanel;
    private int collapsedDialogWidth = -1;
    private int expandedLeftWidth = -1;
    private static final int PREVIEW_PANEL_WIDTH = 420;

    private static JTextArea createPreviewTextArea(boolean editable) {
        JTextArea textArea = new JTextArea();
        textArea.setEditable(editable);
        textArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, textArea.getFont().getSize()));
        textArea.setMargin(JBUI.insets(8));
        return textArea;
    }

    public ProviderDialog(@Nullable Provider existing) {
        super(true);
        this.provider = existing != null ? existing : new Provider();

        setTitle(existing != null ? I18n.t("providerDialog.title.edit") : I18n.t("providerDialog.title.add"));

        dynamicPanel.add(buildClaudePanel(), CliType.CLAUDE.name());
        dynamicPanel.add(buildCodexPanel(), CliType.CODEX.name());
        dynamicPanel.add(buildGeminiPanel(), CliType.GEMINI.name());
        dynamicPanel.add(buildOpenCodePanel(), CliType.OPENCODE.name());

        cliTypeCombo.addActionListener(e -> {
            CliType selected = (CliType) cliTypeCombo.getSelectedItem();
            if (selected != null) {
                ((CardLayout) dynamicPanel.getLayout()).show(dynamicPanel, selected.name());
                refreshPresetButtons(selected);
                updatePreview();
            }
        });

        if (existing != null) {
            cliTypeCombo.setSelectedItem(existing.getCliType());
            nameField.setText(existing.getName());
            loadSettingsConfig(existing.getCliType(), existing.getSettingsConfig());
        } else {
            opencodeNpm.setSelectedItem("@ai-sdk/openai-compatible");
        }

        testConnectionButton.addActionListener(e -> onTestConnection());
        togglePreviewButton.addActionListener(e -> togglePreview());

        init();

        CliType initial = (CliType) cliTypeCombo.getSelectedItem();
        if (initial != null) {
            ((CardLayout) dynamicPanel.getLayout()).show(dynamicPanel, initial.name());
            refreshPresetButtons(initial);
        }

        setupInputListeners();
        setupPreviewSyncListeners();
        updatePreview();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        presetHintLabel.setForeground(new JBColor(new Color(200, 130, 0), new Color(230, 180, 80)));
        presetHintLabel.setBorder(JBUI.Borders.empty(0, 0, 4, 0));
        testConnectionHintLabel.setForeground(JBUI.CurrentTheme.ContextHelp.FOREGROUND);
        testConnectionHintLabel.setBorder(JBUI.Borders.empty(2, 0, 4, 0));
        testStatusLabel.setBorder(JBUI.Borders.empty(0, 0, 4, 0));

        JPanel testPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        testPanel.add(testConnectionButton);
        testPanel.add(Box.createHorizontalStrut(8));
        testPanel.add(togglePreviewButton);
        testPanel.add(Box.createHorizontalStrut(8));
        testPanel.add(testStatusLabel);

        JPanel topPanel = FormBuilder.createFormBuilder()
                .addLabeledComponent(I18n.t("providerDialog.label.preset"), presetButtonsPanel)
                .addComponent(presetHintLabel)
                .addComponent(testPanel)
                .addComponent(testConnectionHintLabel)
                .addSeparator(8)
                .addLabeledComponent(I18n.t("providerDialog.label.cliType"), cliTypeCombo)
                .addLabeledComponent(I18n.t("providerDialog.label.configName"), nameField)
                .getPanel();

        leftPanel = new JPanel(new BorderLayout(0, JBUI.scale(8)));
        leftPanel.setBorder(JBUI.Borders.empty(4, 0));
        leftPanel.add(topPanel, BorderLayout.NORTH);
        leftPanel.add(dynamicPanel, BorderLayout.CENTER);

        setupPreviewPanel();

        splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPanel, previewPanel);
        splitPane.setResizeWeight(1.0);
        splitPane.setDividerLocation(9999);
        splitPane.setBorder(JBUI.Borders.empty());
        splitPane.setDividerSize(0);

        splitPane.setMinimumSize(new Dimension(550, 400));

        previewPanel.setVisible(false);

        return splitPane;
    }


    private void togglePreview() {
        if (!previewVisible) {
            previewVisible = true;
            previewPanel.setVisible(true);
            togglePreviewButton.setText(I18n.t("providerDialog.button.hidePreview"));

            Window window = SwingUtilities.getWindowAncestor(splitPane);
            if (window != null) {
                collapsedDialogWidth = window.getWidth();
            }
            expandedLeftWidth = leftPanel.getWidth() > 0 ? leftPanel.getWidth() : splitPane.getWidth();

            splitPane.setResizeWeight(0.0);
            splitPane.setDividerSize(JBUI.scale(6));
            if (window != null && collapsedDialogWidth > 0) {
                int targetWidth = collapsedDialogWidth + JBUI.scale(PREVIEW_PANEL_WIDTH);
                window.setSize(targetWidth, window.getHeight());
            }

            int dividerLocation = expandedLeftWidth > 0 ? expandedLeftWidth : splitPane.getDividerLocation();
            SwingUtilities.invokeLater(() -> splitPane.setDividerLocation(dividerLocation));
            updatePreview();
        } else {
            previewVisible = false;
            previewPanel.setVisible(false);
            togglePreviewButton.setText(I18n.t("providerDialog.button.showPreview"));
            splitPane.setResizeWeight(1.0);
            splitPane.setDividerSize(0);
            splitPane.setDividerLocation(9999);

            Window window = SwingUtilities.getWindowAncestor(splitPane);
            if (window != null && collapsedDialogWidth > 0) {
                window.setSize(collapsedDialogWidth, window.getHeight());
            }
        }

        splitPane.revalidate();
        splitPane.repaint();
    }

    private void setupPreviewPanel() {
        previewPanel.removeAll();

        JPanel containerPanel = new JPanel(new BorderLayout());

        JScrollPane previewScrollPane = new JScrollPane(previewTextArea);
        previewScrollPane.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(),
                I18n.t("providerDialog.label.preview")));

        codexPreviewTabs.addTab(I18n.t("providerDialog.preview.authJson"), new JScrollPane(codexAuthPreview));
        codexPreviewTabs.addTab(I18n.t("providerDialog.preview.configToml"), new JScrollPane(codexTomlPreview));
        codexPreviewTabs.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(),
                I18n.t("providerDialog.label.preview")));

        containerPanel.add(previewScrollPane, BorderLayout.CENTER);
        containerPanel.add(codexPreviewTabs, BorderLayout.CENTER);

        previewPanel.add(containerPanel, BorderLayout.CENTER);
    }

    // =====================================================================
    // =====================================================================

    private void setupInputListeners() {
        cliTypeCombo.addActionListener(e -> {
            if (!updatingFromPreview) {
                updatePreview();
            }
        });

        nameField.getDocument().addDocumentListener(createDocumentListener());

        addTextFieldListener(claudeApiKey);
        addTextFieldListener(claudeBaseUrl);
        addTextFieldListener(claudeModel);
        addTextFieldListener(claudeHaiku);
        addTextFieldListener(claudeSonnet);
        addTextFieldListener(claudeOpus);
        claudeApiKeyField.addActionListener(e -> {
            if (!updatingFromPreview) updatePreview();
        });
        claudeEffortLevel.addActionListener(e -> {
            if (!updatingFromPreview) updatePreview();
        });
        claudeAlwaysThinkingEnabled.addActionListener(e -> {
            if (!updatingFromPreview) updatePreview();
        });

        addTextFieldListener(codexApiKey);
        addTextFieldListener(codexBaseUrl);
        addTextFieldListener(codexModel);
        codexReasoningEffort.addActionListener(e -> {
            if (!updatingFromPreview) updatePreview();
        });

        addTextFieldListener(geminiApiKey);
        addTextFieldListener(geminiBaseUrl);
        addTextFieldListener(geminiModel);

        addTextFieldListener(opencodeApiKey);
        addTextFieldListener(opencodeBaseUrl);
        opencodeNpm.addActionListener(e -> {
            if (!updatingFromPreview) updatePreview();
        });
    }

    private void addTextFieldListener(JTextField field) {
        field.getDocument().addDocumentListener(createDocumentListener());
    }

    private void setupPreviewSyncListeners() {
        addPreviewDocumentListener(previewTextArea);
        addPreviewDocumentListener(codexAuthPreview);
        addPreviewDocumentListener(codexTomlPreview);
    }

    private void addPreviewDocumentListener(JTextArea textArea) {
        textArea.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                syncPreviewToFormIfNeeded();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                syncPreviewToFormIfNeeded();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                syncPreviewToFormIfNeeded();
            }
        });
    }

    private void syncPreviewToFormIfNeeded() {
        if (!previewVisible || updatingFromPreview) {
            return;
        }
        applyPreviewToForm(false);
    }

    private void withPreviewSyncMuted(Runnable runnable) {
        boolean previous = updatingFromPreview;
        updatingFromPreview = true;
        try {
            runnable.run();
        } finally {
            updatingFromPreview = previous;
        }
    }

    private DocumentListener createDocumentListener() {
        return new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                if (!updatingFromPreview) updatePreview();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                if (!updatingFromPreview) updatePreview();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                if (!updatingFromPreview) updatePreview();
            }
        };
    }

    private void updatePreview() {
        if (!previewVisible) {
            return;
        }

        CliType cliType = (CliType) cliTypeCombo.getSelectedItem();
        if (cliType == null) {
            return;
        }

        JsonObject config = buildSettingsConfig(cliType);

        if (cliType == CliType.CODEX) {
            previewPanel.removeAll();
            previewPanel.add(codexPreviewTabs, BorderLayout.CENTER);

            withPreviewSyncMuted(() -> {
                if (config.has("auth")) {
                    codexAuthPreview.setText(PREVIEW_GSON.toJson(config.getAsJsonObject("auth")));
                } else {
                    codexAuthPreview.setText("{}");
                }

                if (config.has("config")) {
                    codexTomlPreview.setText(config.get("config").getAsString());
                } else {
                    codexTomlPreview.setText("");
                }
            });
        } else {
            previewPanel.removeAll();
            JScrollPane scrollPane = new JScrollPane(previewTextArea);
            scrollPane.setBorder(BorderFactory.createTitledBorder(
                    BorderFactory.createEtchedBorder(),
                    I18n.t("providerDialog.label.preview")));
            previewPanel.add(scrollPane, BorderLayout.CENTER);

            String preview = formatPreviewContent(cliType, config);
            withPreviewSyncMuted(() -> previewTextArea.setText(preview));
        }

        previewPanel.revalidate();
        previewPanel.repaint();
    }

    // =====================================================================
    // =====================================================================

    private void applyPreviewToForm(boolean showErrorDialog) {
        CliType cliType = (CliType) cliTypeCombo.getSelectedItem();
        if (cliType == null) {
            return;
        }

        try {
            updatingFromPreview = true;

            switch (cliType) {
                case CLAUDE -> applyClaudePreviewToForm();
                case CODEX -> applyCodexPreviewToForm();
                case GEMINI -> applyGeminiPreviewToForm();
                case OPENCODE -> applyOpenCodePreviewToForm();
            }

            testStatusLabel.setText(" ");
        } catch (Exception e) {
            if (showErrorDialog) {
                Messages.showErrorDialog(
                        I18n.t("providerDialog.preview.parseError", e.getMessage()),
                        I18n.t("providerDialog.preview.parseErrorTitle"));
            }
        } finally {
            updatingFromPreview = false;
        }
    }

    private void applyClaudePreviewToForm() {
        String text = previewTextArea.getText().trim();
        if (text.isEmpty() || text.equals("{}")) {
            return;
        }

        StringBuilder jsonBuilder = new StringBuilder();
        for (String line : text.split("\n")) {
            if (!line.trim().startsWith("//")) {
                jsonBuilder.append(line).append("\n");
            }
        }

        JsonObject config = JsonParser.parseString(jsonBuilder.toString().trim()).getAsJsonObject();
        if (config.has("env") || config.has("alwaysThinkingEnabled") || config.has("effortLevel")) {
            loadClaudeConfig(config);
        }
    }

    private void applyCodexPreviewToForm() {
        String authText = codexAuthPreview.getText().trim();
        if (!authText.isEmpty() && !authText.equals("{}")) {
            try {
                JsonObject auth = JsonParser.parseString(authText).getAsJsonObject();
                if (auth.has("OPENAI_API_KEY")) {
                    codexApiKey.setText(auth.get("OPENAI_API_KEY").getAsString());
                }
            } catch (Exception ignored) {
            }
        }

        String tomlText = codexTomlPreview.getText().trim();
        if (!tomlText.isEmpty()) {
            for (String line : tomlText.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("model =")) {
                    codexModel.setText(extractTomlValue(trimmed));
                } else if (trimmed.startsWith("model_reasoning_effort =")) {
                    codexReasoningEffort.setSelectedItem(extractTomlValue(trimmed));
                } else if (trimmed.startsWith("base_url =")) {
                    codexBaseUrl.setText(extractTomlValue(trimmed));
                }
            }
        }
    }

    private void applyGeminiPreviewToForm() {
        String text = previewTextArea.getText().trim();
        if (text.isEmpty()) {
            return;
        }

        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int eqIndex = trimmed.indexOf('=');
            if (eqIndex > 0) {
                String key = trimmed.substring(0, eqIndex).trim();
                String value = trimmed.substring(eqIndex + 1).trim();
                switch (key) {
                    case "GEMINI_API_KEY" -> geminiApiKey.setText(value);
                    case "GOOGLE_GEMINI_BASE_URL" -> geminiBaseUrl.setText(value);
                    case "GEMINI_MODEL" -> geminiModel.setText(value);
                }
            }
        }
    }

    private void applyOpenCodePreviewToForm() {
        String text = previewTextArea.getText().trim();
        if (text.isEmpty()) {
            return;
        }

        StringBuilder jsonBuilder = new StringBuilder();
        for (String line : text.split("\n")) {
            if (!line.trim().startsWith("//")) {
                jsonBuilder.append(line).append("\n");
            }
        }

        JsonObject config = JsonParser.parseString(jsonBuilder.toString().trim()).getAsJsonObject();
        if (config.has("provider")) {
            JsonObject provider = config.getAsJsonObject("provider");
            loadOpenCodeConfig(provider);
        }
    }

    private String formatPreviewContent(CliType cliType, JsonObject config) {
        return switch (cliType) {
            case CLAUDE -> formatClaudePreview(config);
            case GEMINI -> formatGeminiPreview(config);
            case OPENCODE -> formatOpenCodePreview(config);
            case CODEX -> "";
        };
    }

    private String formatClaudePreview(JsonObject config) {
        if (!config.has("env") && !config.has("effortLevel") && !config.has("alwaysThinkingEnabled")) {
            return "{}";
        }
        JsonObject preview = new JsonObject();
        if (config.has("env")) {
            preview.add("env", config.getAsJsonObject("env"));
        }
        if (config.has("effortLevel")) {
            preview.add("effortLevel", config.get("effortLevel"));
        }
        if (config.has("alwaysThinkingEnabled")) {
            preview.add("alwaysThinkingEnabled", config.get("alwaysThinkingEnabled"));
        }

        StringBuilder sb = new StringBuilder();
        sb.append("// ").append(I18n.t("providerDialog.preview.settingsJson")).append("\n");
        sb.append(PREVIEW_GSON.toJson(preview)).append("\n");
        return sb.toString();
    }
    private String formatGeminiPreview(JsonObject config) {
        if (!config.has("env")) {
            return "# " + I18n.t("providerDialog.preview.envFile") + "\n";
        }
        JsonObject env = config.getAsJsonObject("env");

        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(I18n.t("providerDialog.preview.envFile")).append("\n");
        for (String key : env.keySet()) {
            sb.append(key).append("=").append(env.get(key).getAsString()).append("\n");
        }
        return sb.toString();
    }

    private String formatOpenCodePreview(JsonObject config) {
        JsonObject preview = new JsonObject();
        JsonObject providerObj = new JsonObject();

        if (config.has("npm")) {
            providerObj.add("npm", config.get("npm"));
        }
        if (config.has("options")) {
            providerObj.add("options", config.getAsJsonObject("options"));
        }
        if (config.has("models")) {
            providerObj.add("models", config.getAsJsonObject("models"));
        }

        preview.add("provider", providerObj);

        StringBuilder sb = new StringBuilder();
        sb.append("// opencode.json (provider)\n");
        sb.append(PREVIEW_GSON.toJson(preview));
        return sb.toString();
    }

    // =====================================================================
    // =====================================================================

    private void refreshPresetButtons(CliType cliType) {
        presetButtonsPanel.removeAll();
        currentPresets = ProviderPresets.forCli(cliType);

        JButton customBtn = createPresetButton(PRESET_NONE, true);
        presetButtonsPanel.add(customBtn);

        for (ProviderPresets.Preset preset : currentPresets) {
            JButton btn = createPresetButton(preset.name(), false);
            presetButtonsPanel.add(btn);
        }

        selectedPreset = PRESET_NONE;
        presetHintLabel.setText(" ");
        updatePresetButtonStyles();

        presetButtonsPanel.revalidate();
        presetButtonsPanel.repaint();
    }

    private JButton createPresetButton(String text, boolean isCustom) {
        JButton btn = new JButton(text);
        btn.setFocusPainted(false);
        btn.putClientProperty("presetName", text);
        btn.addActionListener(e -> onPresetSelected(text));

        btn.setMargin(JBUI.insets(6, 12));
        btn.setPreferredSize(new Dimension(JBUI.scale(100), JBUI.scale(32)));

        return btn;
    }

    private void onPresetSelected(String presetName) {
        selectedPreset = presetName;
        updatePresetButtonStyles();

        if (PRESET_NONE.equals(presetName)) {
            presetHintLabel.setText(" ");
            testStatusLabel.setText(" ");
            updatePreview();
            return;
        }

        CliType cliType = (CliType) cliTypeCombo.getSelectedItem();
        for (ProviderPresets.Preset preset : currentPresets) {
            if (preset.name().equals(presetName)) {
                nameField.setText(preset.name());
                clearAllFields(cliType);
                loadSettingsConfig(cliType, preset.settingsConfig());

                if ("Official Login".equals(preset.name())) {
                    presetHintLabel.setText(OFFICIAL_HINT);
                } else {
                    presetHintLabel.setText(I18n.t("providerDialog.preset.fillHint"));
                }
                testStatusLabel.setText(" ");
                updatePreview();
                return;
            }
        }
    }

    private void updatePresetButtonStyles() {
        for (Component comp : presetButtonsPanel.getComponents()) {
            if (comp instanceof JButton btn) {
                String presetName = (String) btn.getClientProperty("presetName");
                if (selectedPreset.equals(presetName)) {
                    btn.setBorder(BorderFactory.createCompoundBorder(
                            BorderFactory
                                    .createLineBorder(new JBColor(new Color(59, 130, 246), new Color(96, 165, 250)), 2),
                            BorderFactory.createEmptyBorder(4, 10, 4, 10)));
                    btn.setFont(btn.getFont().deriveFont(Font.BOLD));
                } else {
                    btn.setBorder(BorderFactory.createCompoundBorder(
                            BorderFactory.createLineBorder(JBColor.border()),
                            BorderFactory.createEmptyBorder(5, 11, 5, 11)));
                    btn.setFont(btn.getFont().deriveFont(Font.PLAIN));
                }
            }
        }
    }

    private void clearAllFields(CliType cliType) {
        switch (cliType) {
            case CLAUDE -> {
                claudeApiKey.setText("");
                claudeBaseUrl.setText("");
                claudeModel.setText("");
                claudeHaiku.setText("");
                claudeSonnet.setText("");
                claudeOpus.setText("");
                claudeEffortLevel.setSelectedItem("");
                claudeAlwaysThinkingEnabled.setSelectedItem("");
            }
            case CODEX -> {
                codexApiKey.setText("");
                codexBaseUrl.setText("");
                codexModel.setText("");
            }
            case GEMINI -> {
                geminiApiKey.setText("");
                geminiBaseUrl.setText("");
                geminiModel.setText("");
            }
            case OPENCODE -> {
                opencodeApiKey.setText("");
                opencodeBaseUrl.setText("");
                clearOpenCodeModelFields();
            }
        }
    }

    // =====================================================================
    // =====================================================================

    private JPanel buildClaudePanel() {
        JPanel form = FormBuilder.createFormBuilder()
                .addLabeledComponent(I18n.t("providerDialog.label.keyFieldName"), claudeApiKeyField)
                .addLabeledComponent("API Key:", claudeApiKey)
                .addLabeledComponent("Base URL:", claudeBaseUrl)
                .addSeparator(8)
                .addLabeledComponent(I18n.t("providerDialog.label.mainModel"), claudeModel)
                .addLabeledComponent("Haiku:", claudeHaiku)
                .addLabeledComponent("Sonnet:", claudeSonnet)
                .addLabeledComponent("Opus:", claudeOpus)
                .addSeparator(8)
                .addLabeledComponent(I18n.t("providerDialog.label.effortLevel"), claudeEffortLevel)
                .addLabeledComponent(I18n.t("providerDialog.label.alwaysThinkingEnabled"), claudeAlwaysThinkingEnabled)
                .getPanel();
        return wrapWithTitledBorder(form, I18n.t("providerDialog.border.claude"));
    }

    private JPanel buildCodexPanel() {
        JPanel form = FormBuilder.createFormBuilder()
                .addLabeledComponent("API Key:", codexApiKey)
                .addLabeledComponent("Base URL:", codexBaseUrl)
                .addLabeledComponent(I18n.t("providerDialog.label.model"), codexModel)
                .addSeparator(8)
                .addLabeledComponent(I18n.t("providerDialog.label.reasoningEffort"), codexReasoningEffort)
                .getPanel();
        return wrapWithTitledBorder(form, I18n.t("providerDialog.border.codex"));
    }

    private JPanel buildGeminiPanel() {
        JPanel form = FormBuilder.createFormBuilder()
                .addLabeledComponent("API Key:", geminiApiKey)
                .addLabeledComponent("Base URL:", geminiBaseUrl)
                .addLabeledComponent(I18n.t("providerDialog.label.model"), geminiModel)
                .getPanel();
        return wrapWithTitledBorder(form, I18n.t("providerDialog.border.gemini"));
    }

    private JPanel buildOpenCodePanel() {
        opencodeModelsPanel.setLayout(new BoxLayout(opencodeModelsPanel, BoxLayout.Y_AXIS));
        addOpenCodeModelField("");

        JButton addModelBtn = new JButton(I18n.t("providerDialog.button.addModel"));
        addModelBtn.addActionListener(e -> addOpenCodeModelField(""));

        JPanel modelsContainer = new JPanel(new BorderLayout());
        modelsContainer.add(opencodeModelsPanel, BorderLayout.CENTER);
        modelsContainer.add(addModelBtn, BorderLayout.SOUTH);

        JPanel form = FormBuilder.createFormBuilder()
                .addLabeledComponent(I18n.t("providerDialog.label.npmPackage"), opencodeNpm)
                .addLabeledComponent("API Key:", opencodeApiKey)
                .addLabeledComponent("Base URL:", opencodeBaseUrl)
                .addLabeledComponent(I18n.t("providerDialog.label.models"), modelsContainer)
                .getPanel();
        return wrapWithTitledBorder(form, I18n.t("providerDialog.border.opencode"));
    }

    private void addOpenCodeModelField(String modelName) {
        addOpenCodeModelField(modelName, null);
    }

    private void addOpenCodeModelField(String modelName, String reasoningEffort) {
        JPanel row = new JPanel(new BorderLayout(4, 0));

        JTextField modelField = new JTextField(20);
        modelField.setText(modelName);

        JComboBox<String> effortCombo = new JComboBox<>(
                new String[] { "", "xhigh", "high", "medium", "low" });
        effortCombo.setEditable(true);
        if (reasoningEffort != null && !reasoningEffort.isEmpty()) {
            effortCombo.setSelectedItem(reasoningEffort);
        }

        modelField.getDocument().addDocumentListener(createDocumentListener());
        effortCombo.addActionListener(e -> {
            if (!updatingFromPreview) updatePreview();
        });

        JPanel centerPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        centerPanel.add(modelField);
        centerPanel.add(new JLabel(I18n.t("providerDialog.label.reasoningEffort")));
        centerPanel.add(effortCombo);

        JButton removeBtn = new JButton("x");
        removeBtn.setToolTipText(I18n.t("providerDialog.tooltip.removeModel"));
        removeBtn.setMargin(JBUI.insets(0, 6));

        ModelRow modelRow = new ModelRow(modelField, effortCombo, row);
        opencodeModelRows.add(modelRow);

        removeBtn.addActionListener(e -> {
            opencodeModelRows.remove(modelRow);
            opencodeModelsPanel.remove(row);
            opencodeModelsPanel.revalidate();
            opencodeModelsPanel.repaint();
            if (!updatingFromPreview) updatePreview();
        });

        row.add(centerPanel, BorderLayout.CENTER);
        row.add(removeBtn, BorderLayout.EAST);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));

        opencodeModelsPanel.add(row);
        opencodeModelsPanel.revalidate();
        opencodeModelsPanel.repaint();
    }

    private JPanel wrapWithTitledBorder(JPanel content, String title) {
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(), title));
        content.setBorder(JBUI.Borders.empty(8, 12, 12, 12));
        wrapper.add(content, BorderLayout.CENTER);
        return wrapper;
    }

    // =====================================================================
    // =====================================================================

    private void loadSettingsConfig(CliType cliType, JsonObject config) {
        if (config == null)
            return;
        switch (cliType) {
            case CLAUDE -> loadClaudeConfig(config);
            case CODEX -> loadCodexConfig(config);
            case GEMINI -> loadGeminiConfig(config);
            case OPENCODE -> loadOpenCodeConfig(config);
        }
    }

    private void loadClaudeConfig(JsonObject config) {
        JsonObject env = config.has("env") ? config.getAsJsonObject("env") : new JsonObject();
        if (env.has("ANTHROPIC_API_KEY")) {
            claudeApiKeyField.setSelectedItem("ANTHROPIC_API_KEY");
            claudeApiKey.setText(env.get("ANTHROPIC_API_KEY").getAsString());
        } else if (env.has("ANTHROPIC_AUTH_TOKEN")) {
            claudeApiKeyField.setSelectedItem("ANTHROPIC_AUTH_TOKEN");
            claudeApiKey.setText(env.get("ANTHROPIC_AUTH_TOKEN").getAsString());
        }
        setFieldFromJson(env, "ANTHROPIC_BASE_URL", claudeBaseUrl);
        setFieldFromJson(env, "ANTHROPIC_MODEL", claudeModel);
        setFieldFromJson(env, "ANTHROPIC_DEFAULT_HAIKU_MODEL", claudeHaiku);
        setFieldFromJson(env, "ANTHROPIC_DEFAULT_SONNET_MODEL", claudeSonnet);
        setFieldFromJson(env, "ANTHROPIC_DEFAULT_OPUS_MODEL", claudeOpus);
        if (config.has("effortLevel") && !config.get("effortLevel").isJsonNull()) {
            claudeEffortLevel.setSelectedItem(config.get("effortLevel").getAsString());
        } else if (env.has("CLAUDE_CODE_EFFORT_LEVEL")) {
            // 兼容历史配置：旧版本将 effort 写在 env.CLAUDE_CODE_EFFORT_LEVEL
            claudeEffortLevel.setSelectedItem(env.get("CLAUDE_CODE_EFFORT_LEVEL").getAsString());
        }
        if (config.has("alwaysThinkingEnabled") && !config.get("alwaysThinkingEnabled").isJsonNull()) {
            String alwaysThinkingValue = config.get("alwaysThinkingEnabled").getAsString();
            if ("true".equalsIgnoreCase(alwaysThinkingValue) || "false".equalsIgnoreCase(alwaysThinkingValue)) {
                claudeAlwaysThinkingEnabled.setSelectedItem(alwaysThinkingValue.toLowerCase());
            } else {
                claudeAlwaysThinkingEnabled.setSelectedItem("");
            }
        } else {
            claudeAlwaysThinkingEnabled.setSelectedItem("");
        }
    }
    private void loadCodexConfig(JsonObject config) {
        if (config.has("auth")) {
            JsonObject auth = config.getAsJsonObject("auth");
            setFieldFromJson(auth, "OPENAI_API_KEY", codexApiKey);
        }
        if (config.has("config")) {
            String toml = config.get("config").getAsString();
            for (String line : toml.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("model =")) {
                    codexModel.setText(extractTomlValue(trimmed));
                } else if (trimmed.startsWith("model_reasoning_effort =")) {
                    codexReasoningEffort.setSelectedItem(extractTomlValue(trimmed));
                } else if (trimmed.startsWith("base_url =")) {
                    codexBaseUrl.setText(extractTomlValue(trimmed));
                }
            }
        }
    }

    private void loadGeminiConfig(JsonObject config) {
        if (!config.has("env"))
            return;
        JsonObject env = config.getAsJsonObject("env");
        setFieldFromJson(env, "GEMINI_API_KEY", geminiApiKey);
        setFieldFromJson(env, "GOOGLE_GEMINI_BASE_URL", geminiBaseUrl);
        setFieldFromJson(env, "GEMINI_MODEL", geminiModel);
    }

    private void loadOpenCodeConfig(JsonObject config) {
        if (config.has("npm")) {
            opencodeNpm.setSelectedItem(config.get("npm").getAsString());
        }
        if (config.has("options")) {
            JsonObject opts = config.getAsJsonObject("options");
            setFieldFromJson(opts, "apiKey", opencodeApiKey);
            setFieldFromJson(opts, "baseURL", opencodeBaseUrl);
        }
        if (config.has("models")) {
            JsonObject models = config.getAsJsonObject("models");
            clearOpenCodeModelFields();
            for (String modelName : models.keySet()) {
                JsonObject modelDef = models.getAsJsonObject(modelName);
                String reasoningEffort = null;
                if (modelDef.has("options")) {
                    JsonObject modelOpts = modelDef.getAsJsonObject("options");
                    if (modelOpts.has("reasoningEffort")) {
                        reasoningEffort = modelOpts.get("reasoningEffort").getAsString();
                    }
                }
                addOpenCodeModelField(modelName, reasoningEffort);
            }
        }
    }

    private void clearOpenCodeModelFields() {
        opencodeModelRows.clear();
        opencodeModelsPanel.removeAll();
        opencodeModelsPanel.revalidate();
        opencodeModelsPanel.repaint();
    }

    private JsonObject buildSettingsConfig(CliType cliType) {
        return switch (cliType) {
            case CLAUDE -> buildClaudeConfig();
            case CODEX -> buildCodexConfig();
            case GEMINI -> buildGeminiConfig();
            case OPENCODE -> buildOpenCodeConfig();
        };
    }

    private JsonObject buildClaudeConfig() {
        JsonObject config = new JsonObject();
        JsonObject env = new JsonObject();
        String keyFieldName = (String) claudeApiKeyField.getSelectedItem();
        addIfNotBlank(env, keyFieldName, claudeApiKey);
        addIfNotBlank(env, "ANTHROPIC_BASE_URL", claudeBaseUrl);
        addIfNotBlank(env, "ANTHROPIC_MODEL", claudeModel);
        addIfNotBlank(env, "ANTHROPIC_DEFAULT_HAIKU_MODEL", claudeHaiku);
        addIfNotBlank(env, "ANTHROPIC_DEFAULT_SONNET_MODEL", claudeSonnet);
        addIfNotBlank(env, "ANTHROPIC_DEFAULT_OPUS_MODEL", claudeOpus);
        String effortLevel = (String) claudeEffortLevel.getSelectedItem();
        if (effortLevel != null && !effortLevel.isEmpty()) {
            config.addProperty("effortLevel", effortLevel);
        }
        config.add("env", env);

        String alwaysThinkingEnabled = (String) claudeAlwaysThinkingEnabled.getSelectedItem();
        if ("true".equalsIgnoreCase(alwaysThinkingEnabled) || "false".equalsIgnoreCase(alwaysThinkingEnabled)) {
            config.addProperty("alwaysThinkingEnabled", Boolean.parseBoolean(alwaysThinkingEnabled));
        }
        return config;
    }
    private JsonObject buildCodexConfig() {
        JsonObject config = new JsonObject();
        JsonObject auth = new JsonObject();
        addIfNotBlank(auth, "OPENAI_API_KEY", codexApiKey);
        config.add("auth", auth);

        String provider = CODEX_PROVIDER_SLUG;
        String model = codexModel.getText().trim();
        String effort = (String) codexReasoningEffort.getSelectedItem();
        String baseUrl = codexBaseUrl.getText().trim();

        if (!baseUrl.isEmpty()) {
            baseUrl = baseUrl.replaceAll("/+$", "");
            if (!baseUrl.contains("/") || baseUrl.matches("https?://[^/]+")) {
                baseUrl = baseUrl + "/v1";
            }
        }

        StringBuilder toml = new StringBuilder();
        toml.append("model_provider = \"").append(provider).append("\"\n");
        if (!model.isEmpty())
            toml.append("model = \"").append(model).append("\"\n");
        if (effort != null && !effort.isEmpty())
            toml.append("model_reasoning_effort = \"").append(effort).append("\"\n");
        toml.append("disable_response_storage = true\n\n");
        toml.append("[model_providers.").append(provider).append("]\n");
        toml.append("name = \"").append(provider).append("\"\n");
        if (!baseUrl.isEmpty())
            toml.append("base_url = \"").append(baseUrl).append("\"\n");
        toml.append("wire_api = \"responses\"\n");
        toml.append("requires_openai_auth = true\n");

        config.addProperty("config", toml.toString());
        return config;
    }

    private JsonObject buildGeminiConfig() {
        JsonObject config = new JsonObject();
        JsonObject env = new JsonObject();
        addIfNotBlank(env, "GEMINI_API_KEY", geminiApiKey);
        addIfNotBlank(env, "GOOGLE_GEMINI_BASE_URL", geminiBaseUrl);
        addIfNotBlank(env, "GEMINI_MODEL", geminiModel);
        config.add("env", env);
        return config;
    }

    private JsonObject buildOpenCodeConfig() {
        JsonObject config = new JsonObject();
        String npm = ((String) opencodeNpm.getSelectedItem());
        if (npm == null || npm.isBlank())
            npm = "@ai-sdk/openai-compatible";
        config.addProperty("npm", npm.trim());

        JsonObject options = new JsonObject();
        addIfNotBlank(options, "baseURL", opencodeBaseUrl);
        addIfNotBlank(options, "apiKey", opencodeApiKey);
        config.add("options", options);

        JsonObject models = new JsonObject();
        for (ModelRow row : opencodeModelRows) {
            String model = row.nameField.getText().trim();
            if (!model.isEmpty()) {
                JsonObject modelDef = new JsonObject();
                modelDef.addProperty("name", model);

                String effort = (String) row.reasoningEffortCombo.getSelectedItem();
                if (effort != null && !effort.isEmpty()) {
                    JsonObject modelOpts = new JsonObject();
                    modelOpts.addProperty("reasoningEffort", effort);
                    modelDef.add("options", modelOpts);
                }

                models.add(model, modelDef);
            }
        }
        if (!models.isEmpty()) {
            config.add("models", models);
        }
        return config;
    }

    private void setFieldFromJson(JsonObject json, String key, JTextField field) {
        if (json.has(key) && !json.get(key).isJsonNull()) {
            field.setText(json.get(key).getAsString());
        }
    }

    private void addIfNotBlank(JsonObject json, String key, JTextField field) {
        String value = field.getText().trim();
        if (!value.isEmpty())
            json.addProperty(key, value);
    }

    private String extractTomlValue(String line) {
        int eq = line.indexOf('=');
        if (eq < 0)
            return "";
        return line.substring(eq + 1).trim().replace("\"", "");
    }

    @Override
    protected @Nullable ValidationInfo doValidate() {
        if (nameField.getText().isBlank()) {
            return new ValidationInfo(I18n.t("providerDialog.validate.nameRequired"), nameField);
        }
        if ("Official Login".equals(selectedPreset))
            return null;

        CliType cli = (CliType) cliTypeCombo.getSelectedItem();
        if (cli == null) {
            return null;
        }
        return switch (cli) {
            case CLAUDE -> claudeApiKey.getText().isBlank()
                    ? new ValidationInfo(I18n.t("providerDialog.validate.apiKeyRequired"), claudeApiKey)
                    : null;
            case CODEX -> codexApiKey.getText().isBlank()
                    ? new ValidationInfo(I18n.t("providerDialog.validate.apiKeyRequired"), codexApiKey)
                    : null;
            case GEMINI -> geminiApiKey.getText().isBlank()
                    ? new ValidationInfo(I18n.t("providerDialog.validate.apiKeyRequired"), geminiApiKey)
                    : null;
            case OPENCODE -> opencodeApiKey.getText().isBlank()
                    ? new ValidationInfo(I18n.t("providerDialog.validate.apiKeyRequired"), opencodeApiKey)
                    : null;
        };
    }

    public Provider getProvider() {
        CliType cliType = (CliType) cliTypeCombo.getSelectedItem();
        provider.setCliType(cliType);
        provider.setName(nameField.getText().trim());
        provider.setSettingsConfig(buildSettingsConfig(cliType));
        return provider;
    }

    private static JComboBox<String> createEditableCombo(String... items) {
        JComboBox<String> combo = new JComboBox<>(items);
        combo.setEditable(true);
        return combo;
    }

    private void onTestConnection() {
        CliType cliType = (CliType) cliTypeCombo.getSelectedItem();
        if (cliType == null) {
            return;
        }

        ValidationInfo info = doValidate();
        if (info != null) {
            Messages.showWarningDialog(info.message, I18n.t("providerDialog.test.validationTitle"));
            return;
        }

        JsonObject config = buildSettingsConfig(cliType);
        testConnectionButton.setEnabled(false);
        testStatusLabel.setText(I18n.t("providerDialog.test.testing"));
        testStatusLabel.setForeground(JBColor.GRAY);

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            ProviderConnectionTestService.TestResult result = ProviderConnectionTestService.getInstance().test(cliType, config);
            ApplicationManager.getApplication().invokeLater(() -> {
                testConnectionButton.setEnabled(true);
                if (result.success()) {
                    testStatusLabel.setText(I18n.t("providerDialog.test.success", result.durationMs()));
                    testStatusLabel.setForeground(new JBColor(new Color(66, 160, 83), new Color(66, 160, 83)));
                } else {
                    testStatusLabel.setText(I18n.t("providerDialog.test.failed", result.message()));
                    testStatusLabel.setForeground(JBColor.RED);
                }
            }, ModalityState.any());
        });
    }
}
