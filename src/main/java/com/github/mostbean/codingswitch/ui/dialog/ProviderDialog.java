package com.github.mostbean.codingswitch.ui.dialog;

import com.github.mostbean.codingswitch.model.CliType;
import com.github.mostbean.codingswitch.model.Provider;
import com.github.mostbean.codingswitch.model.Provider.AuthMode;
import com.github.mostbean.codingswitch.service.ConfigFileService;
import com.github.mostbean.codingswitch.service.I18n;
import com.github.mostbean.codingswitch.service.PluginSettings;
import com.github.mostbean.codingswitch.service.PluginSettings.SecurityPolicy;
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
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Provider 编辑对话框。
 * 顶部预设平铺按钮，选择后自动填充下方字段。
 */
public class ProviderDialog extends DialogWrapper {

    private static final String PRESET_NONE = I18n.t("providerDialog.preset.custom");
    private static final String OFFICIAL_HINT = I18n.t("providerDialog.preset.officialHint");
    private static final String DEFAULT_OPTION_LABEL = I18n.t("providerDialog.option.default");
    private static final int TEST_STATUS_MAX_LENGTH = 72;

    private final JPanel presetButtonsPanel = new JPanel(new GridLayout(0, 5, 8, 8));
    private final JBLabel presetHintLabel = new JBLabel(" ");

    private final JComboBox<CliType> cliTypeCombo = new JComboBox<>(CliType.values());
    private final JTextField nameField = new JTextField(30);

    private final JTextField claudeApiKey = new JTextField(30);
    private final JTextField claudeBaseUrl = new JTextField(30);
    private final JTextField claudeModel = new JTextField(30);
    private final JBLabel claudeApiKeyLabel = requiredLabel("API Key:");
    private final JBLabel claudeBaseUrlLabel = requiredLabel("Base URL:");
    private final JBLabel claudeModelLabel = requiredLabel(I18n.t("providerDialog.label.mainModel"));
    private final JTextField claudeHaiku = new JTextField(30);
    private final JTextField claudeSonnet = new JTextField(30);
    private final JTextField claudeOpus = new JTextField(30);
    private final JComboBox<String> claudeApiKeyField = new JComboBox<>(
            new String[] { "ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_API_KEY" });
    private final JComboBox<String> claudeEffortLevel = createEditableCombo(
            "", "high", "medium", "low");
    private final JComboBox<String> claudeAlwaysThinkingEnabled = new JComboBox<>(
            new String[] { DEFAULT_OPTION_LABEL, "true", "false" });
    private final JCheckBox claudeTeamModeEnabled = new JCheckBox();
    private final JComboBox<String> claudeDangerousMode = new JComboBox<>(new String[] {
            DEFAULT_OPTION_LABEL, I18n.t("providerDialog.dangerousMode.skipPermissions"),
            I18n.t("providerDialog.dangerousMode.skipAll") });

    private final JTextField codexApiKey = new JTextField(30);
    private final JTextField codexBaseUrl = new JTextField(30);
    private final JTextField codexModel = new JTextField(30);
    private final JBLabel codexApiKeyLabel = requiredLabel("API Key:");
    private final JBLabel codexBaseUrlLabel = requiredLabel("Base URL:");
    private final JBLabel codexModelLabel = requiredLabel(I18n.t("providerDialog.label.model"));
    private final JComboBox<String> codexReasoningEffort = new JComboBox<>(
            new String[] { "xhigh", "high", "medium", "low" });
    private final JComboBox<SecurityPolicy> codexSecurityPolicy = new JComboBox<>(SecurityPolicy.values());
    private final JCheckBox codex1MContext = new JCheckBox();
    private final JCheckBox codexMultiAgent = new JCheckBox();
    private final JCheckBox codexFastMode = new JCheckBox();
    private static final String CODEX_PROVIDER_SLUG = "custom";

    private final JTextField geminiApiKey = new JTextField(30);
    private final JTextField geminiBaseUrl = new JTextField(30);
    private final JTextField geminiModel = new JTextField(30);
    private final JBLabel geminiApiKeyLabel = requiredLabel("API Key:");
    private final JBLabel geminiBaseUrlLabel = requiredLabel("Base URL:");
    private final JBLabel geminiModelLabel = requiredLabel(I18n.t("providerDialog.label.model"));

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
    private final Map<CliType, JsonObject> rawSettingsByCli = new EnumMap<>(CliType.class);
    private final Map<CliType, AuthMode> authModeByCli = new EnumMap<>(CliType.class);
    private String selectedPreset = PRESET_NONE;
    private List<ProviderPresets.Preset> currentPresets = List.of();

    private static final Gson PREVIEW_GSON = new GsonBuilder().setPrettyPrinting().create();
    private final JTextArea previewTextArea = createPreviewTextArea(true);
    private final JTabbedPane codexPreviewTabs = new JTabbedPane();
    private final JTextArea codexAuthPreview = createPreviewTextArea(true);
    private final JTextArea codexTomlPreview = createPreviewTextArea(true);
    private final JPanel previewPanel = new JPanel(new BorderLayout());
    private final JButton togglePreviewButton = new JButton(I18n.t("providerDialog.button.showPreview"));
    private final JButton openDirButton = new JButton(I18n.t("providerDialog.button.openDir"));
    private boolean previewVisible = false;
    private boolean updatingFromPreview = false;
    private JSplitPane splitPane;
    private JPanel leftPanel;
    private int collapsedDialogWidth = -1;
    private int expandedLeftWidth = -1;
    private static final int PREVIEW_PANEL_WIDTH = 420;

    static String abbreviateTestStatus(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replace('\n', ' ').replace('\r', ' ').trim();
        if (normalized.length() <= TEST_STATUS_MAX_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, TEST_STATUS_MAX_LENGTH - 3) + "...";
    }

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
        initializeAuthModes();

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
                applyAuthModeUi(selected);
                updatePreview();
            }
        });

        if (existing != null) {
            cliTypeCombo.setSelectedItem(existing.getCliType());
            nameField.setText(existing.getName());
            loadSettingsConfig(existing.getCliType(), existing.getSettingsConfig(), existing.getAuthMode());
        } else {
            opencodeNpm.setSelectedItem("@ai-sdk/openai-compatible");
        }

        testConnectionButton.addActionListener(e -> onTestConnection());
        togglePreviewButton.addActionListener(e -> togglePreview());
        openDirButton.addActionListener(e -> openConfigDir());

        init();

        CliType initial = (CliType) cliTypeCombo.getSelectedItem();
        if (initial != null) {
            ((CardLayout) dynamicPanel.getLayout()).show(dynamicPanel, initial.name());
            refreshPresetButtons(initial);
            applyAuthModeUi(initial);
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
        testStatusLabel.setBorder(JBUI.Borders.emptyLeft(8));

        JPanel testButtonsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        testButtonsPanel.add(testConnectionButton);
        testButtonsPanel.add(Box.createHorizontalStrut(8));
        testButtonsPanel.add(openDirButton);
        testButtonsPanel.add(Box.createHorizontalStrut(8));
        testButtonsPanel.add(togglePreviewButton);
        testButtonsPanel.add(testStatusLabel);

        JPanel testPanel = new JPanel();
        testPanel.setLayout(new BoxLayout(testPanel, BoxLayout.Y_AXIS));
        testButtonsPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        testPanel.add(testButtonsPanel);

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

        JScrollPane leftScrollPane = new JScrollPane(leftPanel);
        leftScrollPane.setBorder(JBUI.Borders.empty());
        leftScrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        leftScrollPane.getVerticalScrollBar().setUnitIncrement(16);

        setupPreviewPanel();

        splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftScrollPane, previewPanel);
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
        claudeTeamModeEnabled.addActionListener(e -> {
            if (!updatingFromPreview) updatePreview();
        });
        claudeDangerousMode.addActionListener(e -> {
            if (!updatingFromPreview) updatePreview();
        });

        addTextFieldListener(codexApiKey);
        addTextFieldListener(codexBaseUrl);
        addTextFieldListener(codexModel);
        codexReasoningEffort.addActionListener(e -> {
            if (!updatingFromPreview) updatePreview();
        });
        codex1MContext.addActionListener(e -> {
            if (!updatingFromPreview) updatePreview();
        });
        codexMultiAgent.addActionListener(e -> {
            if (!updatingFromPreview) updatePreview();
        });
        codexFastMode.addActionListener(e -> {
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
        rememberRawSettings(CliType.CLAUDE, config);
        loadClaudeConfig(config);
    }

    private void applyCodexPreviewToForm() {
        JsonObject rawCodex = rawSettingsByCli.containsKey(CliType.CODEX)
                ? rawSettingsByCli.get(CliType.CODEX).deepCopy()
                : new JsonObject();

        String authText = codexAuthPreview.getText().trim();
        if (!authText.isEmpty()) {
            try {
                JsonObject auth = JsonParser.parseString(authText).getAsJsonObject();
                rawCodex.add("auth", auth.deepCopy());
                if (auth.has("OPENAI_API_KEY")) {
                    codexApiKey.setText(auth.get("OPENAI_API_KEY").getAsString());
                }
            } catch (Exception ignored) {
            }
        } else {
            rawCodex.remove("auth");
        }

        String tomlText = codexTomlPreview.getText().trim();
        if (!tomlText.isEmpty()) {
            rawCodex.addProperty("config", tomlText);
            boolean has1MContext = false;
            boolean hasFastMode = false;
            for (String line : tomlText.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("model =")) {
                    codexModel.setText(extractTomlValue(trimmed));
                } else if (trimmed.startsWith("model_reasoning_effort =")) {
                    codexReasoningEffort.setSelectedItem(extractTomlValue(trimmed));
                } else if (trimmed.startsWith("base_url =")) {
                    codexBaseUrl.setText(extractTomlValue(trimmed));
                } else if (trimmed.startsWith("model_context_window =")) {
                    String value = extractTomlValue(trimmed);
                    if ("1000000".equals(value)) {
                        has1MContext = true;
                    }
                } else if (trimmed.startsWith("service_tier =")) {
                    hasFastMode = "fast".equalsIgnoreCase(extractTomlValue(trimmed));
                } else if (trimmed.startsWith("fast_mode =")) {
                    hasFastMode = "true".equalsIgnoreCase(extractTomlValue(trimmed));
                }
            }
            codex1MContext.setSelected(has1MContext);
            codexFastMode.setSelected(hasFastMode);
        } else {
            rawCodex.remove("config");
        }

        rememberRawSettings(CliType.CODEX, rawCodex);
    }

    private void applyGeminiPreviewToForm() {
        String text = previewTextArea.getText().trim();
        if (text.isEmpty()) {
            return;
        }

        JsonObject rawGemini = rawSettingsByCli.containsKey(CliType.GEMINI)
                ? rawSettingsByCli.get(CliType.GEMINI).deepCopy()
                : new JsonObject();
        JsonObject env = new JsonObject();

        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int eqIndex = trimmed.indexOf('=');
            if (eqIndex > 0) {
                String key = trimmed.substring(0, eqIndex).trim();
                String value = trimmed.substring(eqIndex + 1).trim();
                env.addProperty(key, value);
                switch (key) {
                    case "GEMINI_API_KEY" -> geminiApiKey.setText(value);
                    case "GOOGLE_GEMINI_BASE_URL" -> geminiBaseUrl.setText(value);
                    case "GEMINI_MODEL" -> geminiModel.setText(value);
                }
            }
        }

        rawGemini.add("env", env);
        rememberRawSettings(CliType.GEMINI, rawGemini);
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
        if (config.has("provider") && config.get("provider").isJsonObject()) {
            JsonObject provider = config.getAsJsonObject("provider");
            rememberRawSettings(CliType.OPENCODE, provider);
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
        StringBuilder sb = new StringBuilder();
        sb.append("// ").append(I18n.t("providerDialog.preview.settingsJson")).append("\n");
        sb.append(PREVIEW_GSON.toJson(config != null ? config : new JsonObject())).append("\n");
        return sb.toString();
    }
    private String formatGeminiPreview(JsonObject config) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(I18n.t("providerDialog.preview.envFile")).append("\n");

        if (config != null && config.has("env") && config.get("env").isJsonObject()) {
            JsonObject env = config.getAsJsonObject("env");
            for (String key : env.keySet()) {
                if (env.get(key) != null && !env.get(key).isJsonNull()) {
                    sb.append(key).append("=").append(env.get(key).getAsString()).append("\n");
                }
            }
        }
        return sb.toString();
    }

    private String formatOpenCodePreview(JsonObject config) {
        JsonObject preview = new JsonObject();
        preview.add("provider", config != null ? config : new JsonObject());

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

        selectedPreset = resolveSelectedPreset(cliType);
        presetHintLabel.setText(getCurrentAuthMode(cliType) == AuthMode.OFFICIAL_LOGIN ? OFFICIAL_HINT : " ");
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

        CliType cliType = (CliType) cliTypeCombo.getSelectedItem();
        if (cliType == null) {
            return;
        }

        if (PRESET_NONE.equals(presetName)) {
            setAuthMode(cliType, AuthMode.API_KEY);
            presetHintLabel.setText(" ");
            testStatusLabel.setText(" ");
            updatePreview();
            return;
        }

        for (ProviderPresets.Preset preset : currentPresets) {
            if (preset.name().equals(presetName)) {
                nameField.setText(preset.name());
                clearAllFields(cliType);
                loadSettingsConfig(cliType, preset.settingsConfig(), preset.authMode());

                if (preset.authMode() == AuthMode.OFFICIAL_LOGIN) {
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

    private void initializeAuthModes() {
        for (CliType cliType : CliType.values()) {
            AuthMode authMode = cliType == provider.getCliType() ? provider.getAuthMode() : AuthMode.API_KEY;
            authModeByCli.put(cliType, cliType == CliType.OPENCODE ? AuthMode.API_KEY : authMode);
        }
    }

    private AuthMode getCurrentAuthMode(CliType cliType) {
        if (cliType == null || cliType == CliType.OPENCODE) {
            return AuthMode.API_KEY;
        }
        return authModeByCli.getOrDefault(cliType, AuthMode.API_KEY);
    }

    private void setAuthMode(CliType cliType, AuthMode authMode) {
        if (cliType == null || cliType == CliType.OPENCODE) {
            return;
        }
        authModeByCli.put(cliType, authMode != null ? authMode : AuthMode.API_KEY);
        if (cliType == cliTypeCombo.getSelectedItem()) {
            applyAuthModeUi(cliType);
        }
    }

    private String resolveSelectedPreset(CliType cliType) {
        if (getCurrentAuthMode(cliType) != AuthMode.OFFICIAL_LOGIN) {
            return PRESET_NONE;
        }
        return currentPresets.stream()
                .filter(preset -> preset.authMode() == AuthMode.OFFICIAL_LOGIN)
                .map(ProviderPresets.Preset::name)
                .findFirst()
                .orElse(PRESET_NONE);
    }

    private void applyAuthModeUi(CliType cliType) {
        boolean officialLogin = getCurrentAuthMode(cliType) == AuthMode.OFFICIAL_LOGIN;

        switch (cliType) {
            case CLAUDE -> {
                claudeApiKeyField.setEnabled(!officialLogin);
                claudeApiKey.setEnabled(!officialLogin);
                claudeBaseUrl.setEnabled(!officialLogin);
                claudeModel.setEnabled(!officialLogin);
                claudeHaiku.setEnabled(!officialLogin);
                claudeSonnet.setEnabled(!officialLogin);
                claudeOpus.setEnabled(!officialLogin);
                setRequiredState(claudeApiKeyLabel, !officialLogin);
                setRequiredState(claudeBaseUrlLabel, !officialLogin);
                setRequiredState(claudeModelLabel, !officialLogin);
            }
            case CODEX -> {
                codexApiKey.setEnabled(!officialLogin);
                codexBaseUrl.setEnabled(!officialLogin);
                codexModel.setEnabled(true);
                codexReasoningEffort.setEnabled(true);
                codex1MContext.setEnabled(true);
                codexMultiAgent.setEnabled(true);
                codexFastMode.setEnabled(true);
                setRequiredState(codexApiKeyLabel, !officialLogin);
                setRequiredState(codexBaseUrlLabel, !officialLogin);
                setRequiredState(codexModelLabel, !officialLogin);
            }
            case GEMINI -> {
                geminiApiKey.setEnabled(!officialLogin);
                geminiBaseUrl.setEnabled(!officialLogin);
                geminiModel.setEnabled(!officialLogin);
                setRequiredState(geminiApiKeyLabel, !officialLogin);
                setRequiredState(geminiBaseUrlLabel, !officialLogin);
                setRequiredState(geminiModelLabel, !officialLogin);
            }
            case OPENCODE -> {
            }
        }
    }

    private void clearAllFields(CliType cliType) {
        switch (cliType) {
            case CLAUDE -> {
                claudeApiKeyField.setSelectedItem("ANTHROPIC_AUTH_TOKEN");
                claudeApiKey.setText("");
                claudeBaseUrl.setText("");
                claudeModel.setText("");
                claudeHaiku.setText("");
                claudeSonnet.setText("");
                claudeOpus.setText("");
                claudeEffortLevel.setSelectedItem("");
                claudeAlwaysThinkingEnabled.setSelectedIndex(0);
                claudeTeamModeEnabled.setSelected(false);
                claudeDangerousMode.setSelectedIndex(0);
            }
            case CODEX -> {
                codexApiKey.setText("");
                codexBaseUrl.setText("");
                codexModel.setText("");
                codexReasoningEffort.setSelectedItem("xhigh");
                codex1MContext.setSelected(false);
                codexMultiAgent.setSelected(false);
                codexFastMode.setSelected(false);
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
        JPanel thinkingRow = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.anchor = GridBagConstraints.WEST;

        gbc.gridx = 0; gbc.weightx = 0; gbc.fill = GridBagConstraints.NONE;
        gbc.insets = JBUI.insets(0, 0, 0, 8);
        thinkingRow.add(claudeAlwaysThinkingEnabled, gbc);

        gbc.gridx = 1; gbc.weightx = 0; gbc.fill = GridBagConstraints.NONE;
        gbc.insets = JBUI.insets(0, 12, 0, 4);
        thinkingRow.add(new JBLabel(I18n.t("providerDialog.label.effortLevel")), gbc);

        gbc.gridx = 2; gbc.weightx = 0; gbc.fill = GridBagConstraints.NONE;
        gbc.insets = JBUI.insets(0, 0, 0, 0);
        thinkingRow.add(claudeEffortLevel, gbc);

        gbc.gridx = 3; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL;
        thinkingRow.add(Box.createHorizontalGlue(), gbc);

        JPanel form = FormBuilder.createFormBuilder()
                .addLabeledComponent(I18n.t("providerDialog.label.keyFieldName"), claudeApiKeyField)
                .addLabeledComponent(claudeApiKeyLabel, claudeApiKey)
                .addLabeledComponent(claudeBaseUrlLabel, claudeBaseUrl)
                .addSeparator(8)
                .addLabeledComponent(claudeModelLabel, claudeModel)
                .addLabeledComponent("Haiku:", claudeHaiku)
                .addLabeledComponent("Sonnet:", claudeSonnet)
                .addLabeledComponent("Opus:", claudeOpus)
                .addSeparator(8)
                .addLabeledComponent(I18n.t("providerDialog.label.alwaysThinkingEnabled"), thinkingRow)
                .addLabeledComponent(I18n.t("providerDialog.label.dangerousMode"), claudeDangerousMode)
                .addLabeledComponent(I18n.t("providerDialog.label.teamModeEnabled"), claudeTeamModeEnabled)
                .getPanel();
        return wrapWithTitledBorder(form, I18n.t("providerDialog.border.claude"));
    }

    private JPanel buildCodexPanel() {
        // 初始化安全策略下拉框渲染器
        codexSecurityPolicy.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(
                JList<?> list,
                Object value,
                int index,
                boolean isSelected,
                boolean cellHasFocus
            ) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof SecurityPolicy policy) {
                    setText(policy.getDisplayName(I18n.currentLanguage()));
                }
                return this;
            }
        });
        codexSecurityPolicy.setSelectedItem(SecurityPolicy.DEFAULT);
        codexSecurityPolicy.addActionListener(e -> {
            if (!updatingFromPreview) updatePreview();
        });

        JPanel form = FormBuilder.createFormBuilder()
                .addLabeledComponent(codexApiKeyLabel, codexApiKey)
                .addLabeledComponent(codexBaseUrlLabel, codexBaseUrl)
                .addLabeledComponent(codexModelLabel, codexModel)
                .addSeparator(8)
                .addLabeledComponent(I18n.t("providerDialog.label.securityPolicy"), codexSecurityPolicy)
                .addLabeledComponent(I18n.t("providerDialog.label.reasoningEffort"), codexReasoningEffort)
                .addComponent(buildCodexOptionsRow())
                .getPanel();
        return wrapWithTitledBorder(form, I18n.t("providerDialog.border.codex"));
    }

    /**
     * 构建 Codex 选项行（三个复选框并排）
     */
    private JPanel buildCodexOptionsRow() {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 16, 0));
        row.add(createCheckboxWithLabel(codex1MContext, I18n.t("providerDialog.label.1mContext")));
        row.add(createCheckboxWithLabel(codexMultiAgent, I18n.t("providerDialog.label.multiAgent")));
        row.add(createCheckboxWithLabel(codexFastMode, I18n.t("providerDialog.label.fastMode")));
        return row;
    }

    private JPanel createCheckboxWithLabel(JCheckBox checkbox, String labelText) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        panel.add(checkbox);
        panel.add(new JLabel(labelText));
        return panel;
    }

    private JPanel buildGeminiPanel() {
        JPanel form = FormBuilder.createFormBuilder()
                .addLabeledComponent(geminiApiKeyLabel, geminiApiKey)
                .addLabeledComponent(geminiBaseUrlLabel, geminiBaseUrl)
                .addLabeledComponent(geminiModelLabel, geminiModel)
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
                .addLabeledComponent(requiredLabel("API Key:"), opencodeApiKey)
                .addLabeledComponent(requiredLabel("Base URL:"), opencodeBaseUrl)
                .addLabeledComponent(requiredLabel(I18n.t("providerDialog.label.models")), modelsContainer)
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

    /**
     * 生成带红色星号的必填标签
     */
    private JBLabel requiredLabel(String labelText) {
        JBLabel label = new JBLabel();
        label.putClientProperty("baseText", labelText);
        setRequiredState(label, true);
        return label;
    }

    private void setRequiredState(JBLabel label, boolean required) {
        String baseText = (String) label.getClientProperty("baseText");
        label.setText(required
                ? "<html>" + baseText + " <font color='red'>*</font></html>"
                : baseText);
    }

    // =====================================================================
    // =====================================================================

    private void loadSettingsConfig(CliType cliType, JsonObject config) {
        loadSettingsConfig(cliType, config, Provider.inferAuthMode(cliType, config));
    }

    private void loadSettingsConfig(CliType cliType, JsonObject config, AuthMode authMode) {
        JsonObject safeConfig = config != null ? config : new JsonObject();
        setAuthMode(cliType, authMode);
        rememberRawSettings(cliType, safeConfig);
        switch (cliType) {
            case CLAUDE -> loadClaudeConfig(safeConfig);
            case CODEX -> loadCodexConfig(safeConfig);
            case GEMINI -> loadGeminiConfig(safeConfig);
            case OPENCODE -> loadOpenCodeConfig(safeConfig);
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
                claudeAlwaysThinkingEnabled.setSelectedIndex(0);
            }
        } else {
            claudeAlwaysThinkingEnabled.setSelectedIndex(0);
        }

        boolean teamModeEnabled = env.has("CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS")
                && "1".equals(env.get("CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS").getAsString());
        claudeTeamModeEnabled.setSelected(teamModeEnabled);

        boolean dangerousSkip = config.has("dangerouslySkipPermissions")
                && config.get("dangerouslySkipPermissions").getAsBoolean();
        boolean promptSkip = config.has("skipDangerousModePermissionPrompt")
                && config.get("skipDangerousModePermissionPrompt").getAsBoolean();
        if (dangerousSkip && promptSkip) {
            claudeDangerousMode.setSelectedItem(I18n.t("providerDialog.dangerousMode.skipAll"));
        } else if (dangerousSkip) {
            claudeDangerousMode.setSelectedItem(I18n.t("providerDialog.dangerousMode.skipPermissions"));
        } else {
            claudeDangerousMode.setSelectedIndex(0);
        }
    }
    private void loadCodexConfig(JsonObject config) {
        if (config.has("auth")) {
            JsonObject auth = config.getAsJsonObject("auth");
            setFieldFromJson(auth, "OPENAI_API_KEY", codexApiKey);
        }
        if (config.has("config")) {
            String toml = config.get("config").getAsString();
            boolean has1MContext = false;
            boolean hasMultiAgent = false;
            boolean hasFastMode = false;
            String approvalPolicy = null;
            String sandboxMode = null;
            for (String line : toml.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("model =")) {
                    codexModel.setText(extractTomlValue(trimmed));
                } else if (trimmed.startsWith("model_reasoning_effort =")) {
                    codexReasoningEffort.setSelectedItem(extractTomlValue(trimmed));
                } else if (trimmed.startsWith("base_url =")) {
                    codexBaseUrl.setText(extractTomlValue(trimmed));
                } else if (trimmed.startsWith("model_context_window =")) {
                    String value = extractTomlValue(trimmed);
                    if ("1000000".equals(value)) {
                        has1MContext = true;
                    }
                } else if (trimmed.startsWith("multi_agent =")) {
                    hasMultiAgent = "true".equalsIgnoreCase(extractTomlValue(trimmed));
                } else if (trimmed.startsWith("service_tier =")) {
                    hasFastMode = "fast".equalsIgnoreCase(extractTomlValue(trimmed));
                } else if (trimmed.startsWith("fast_mode =")) {
                    hasFastMode = "true".equalsIgnoreCase(extractTomlValue(trimmed));
                } else if (trimmed.startsWith("approval_policy =")) {
                    approvalPolicy = extractTomlValue(trimmed);
                } else if (trimmed.startsWith("sandbox_mode =")) {
                    sandboxMode = extractTomlValue(trimmed);
                }
            }
            codex1MContext.setSelected(has1MContext);
            codexMultiAgent.setSelected(hasMultiAgent);
            codexFastMode.setSelected(hasFastMode);

            // 根据读取的 approval_policy 和 sandbox_mode 设置安全策略下拉框
            codexSecurityPolicy.setSelectedItem(resolveSecurityPolicy(approvalPolicy, sandboxMode));
        }
    }

    /**
     * 根据 approval_policy 和 sandbox_mode 解析对应的 SecurityPolicy。
     */
    private SecurityPolicy resolveSecurityPolicy(String approvalPolicy, String sandboxMode) {
        if (approvalPolicy == null && sandboxMode == null) {
            return SecurityPolicy.DEFAULT;
        }
        for (SecurityPolicy policy : SecurityPolicy.values()) {
            if (policy.getApprovalPolicy() != null
                && policy.getApprovalPolicy().equals(approvalPolicy)
                && policy.getSandboxMode() != null
                && policy.getSandboxMode().equals(sandboxMode)) {
                return policy;
            }
        }
        return SecurityPolicy.DEFAULT;
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
        AuthMode authMode = getCurrentAuthMode(cliType);
        JsonObject structured = switch (cliType) {
            case CLAUDE -> authMode == AuthMode.OFFICIAL_LOGIN ? buildClaudeOfficialConfig() : buildClaudeConfig();
            case CODEX -> authMode == AuthMode.OFFICIAL_LOGIN ? buildCodexOfficialConfig() : buildCodexConfig();
            case GEMINI -> authMode == AuthMode.OFFICIAL_LOGIN ? buildGeminiOfficialConfig() : buildGeminiConfig();
            case OPENCODE -> buildOpenCodeConfig();
        };
        JsonObject merged = mergeWithRawSettings(cliType, structured);
        rememberRawSettings(cliType, merged);
        return merged;
    }

    private JsonObject buildClaudeOfficialConfig() {
        JsonObject config = new JsonObject();
        config.add("env", new JsonObject());
        return config;
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
        if (claudeTeamModeEnabled.isSelected()) {
            env.addProperty("CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS", "1");
        }
        String effortLevel = (String) claudeEffortLevel.getSelectedItem();
        if (effortLevel != null && !effortLevel.isEmpty()) {
            config.addProperty("effortLevel", effortLevel);
        }
        config.add("env", env);

        String alwaysThinkingEnabled = (String) claudeAlwaysThinkingEnabled.getSelectedItem();
        if ("true".equalsIgnoreCase(alwaysThinkingEnabled) || "false".equalsIgnoreCase(alwaysThinkingEnabled)) {
            config.addProperty("alwaysThinkingEnabled", Boolean.parseBoolean(alwaysThinkingEnabled));
        }

        String dangerousMode = (String) claudeDangerousMode.getSelectedItem();
        String skipPermissionsLabel = I18n.t("providerDialog.dangerousMode.skipPermissions");
        String skipAllLabel = I18n.t("providerDialog.dangerousMode.skipAll");
        if (skipAllLabel.equals(dangerousMode)) {
            config.addProperty("dangerouslySkipPermissions", true);
            config.addProperty("skipDangerousModePermissionPrompt", true);
        } else if (skipPermissionsLabel.equals(dangerousMode)) {
            config.addProperty("dangerouslySkipPermissions", true);
        }

        return config;
    }
    private JsonObject buildCodexConfig() {
        JsonObject config = new JsonObject();
        JsonObject auth = new JsonObject();
        addIfNotBlank(auth, "OPENAI_API_KEY", codexApiKey);
        config.add("auth", auth);
        config.addProperty("config", buildCodexToml(false));
        return config;
    }

    private String buildCodexToml(boolean officialLogin) {
        String provider = CODEX_PROVIDER_SLUG;
        String model = codexModel.getText().trim();
        String effort = (String) codexReasoningEffort.getSelectedItem();
        String baseUrl = codexBaseUrl.getText().trim();
        boolean enable1MContext = codex1MContext.isSelected();
        boolean enableMultiAgent = codexMultiAgent.isSelected();
        boolean enableFastMode = codexFastMode.isSelected();
        SecurityPolicy securityPolicy = (SecurityPolicy) codexSecurityPolicy.getSelectedItem();

        if (!baseUrl.isEmpty()) {
            baseUrl = baseUrl.replaceAll("/+$", "");
            if (!baseUrl.contains("/") || baseUrl.matches("https?://[^/]+")) {
                baseUrl = baseUrl + "/v1";
            }
        }

        StringBuilder toml = new StringBuilder();
        if (!officialLogin) {
            toml.append("model_provider = \"").append(provider).append("\"\n");
        }
        if (!model.isEmpty())
            toml.append("model = \"").append(model).append("\"\n");
        if (effort != null && !effort.isEmpty())
            toml.append("model_reasoning_effort = \"").append(effort).append("\"\n");

        // 写入安全策略配置（非默认时）
        if (securityPolicy != null && !securityPolicy.isDefault()) {
            if (securityPolicy.getApprovalPolicy() != null) {
                toml.append("approval_policy = \"").append(securityPolicy.getApprovalPolicy()).append("\"\n");
            }
            if (securityPolicy.getSandboxMode() != null) {
                toml.append("sandbox_mode = \"").append(securityPolicy.getSandboxMode()).append("\"\n");
            }
        }

        if (enable1MContext) {
            toml.append("model_context_window = 1000000\n");
            toml.append("model_auto_compact_token_limit = 900000\n");
        }
        if (enableMultiAgent) {
            toml.append("multi_agent = true\n");
        }
        if (enableFastMode) {
            toml.append("service_tier = \"fast\"\n");
        }
        toml.append("disable_response_storage = true\n");

        if (!officialLogin) {
            toml.append("\n");
            toml.append("[model_providers.").append(provider).append("]\n");
            toml.append("name = \"").append(provider).append("\"\n");
            if (!baseUrl.isEmpty())
                toml.append("base_url = \"").append(baseUrl).append("\"\n");
            toml.append("wire_api = \"responses\"\n");
            toml.append("requires_openai_auth = true\n");
        }

        return toml.toString();
    }

    private JsonObject buildCodexOfficialConfig() {
        JsonObject config = new JsonObject();
        config.add("auth", new JsonObject());
        config.addProperty("config", buildCodexToml(true));
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

    private JsonObject buildGeminiOfficialConfig() {
        JsonObject config = new JsonObject();
        config.add("env", new JsonObject());
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

    private void rememberRawSettings(CliType cliType, JsonObject config) {
        rawSettingsByCli.put(cliType, config != null ? config.deepCopy() : new JsonObject());
    }

    private JsonObject mergeWithRawSettings(CliType cliType, JsonObject structured) {
        JsonObject merged = structured != null ? structured.deepCopy() : new JsonObject();
        JsonObject raw = rawSettingsByCli.get(cliType);
        if (raw == null) {
            return merged;
        }

        switch (cliType) {
            case CLAUDE -> mergeClaudeRaw(raw, merged);
            case CODEX -> mergeCodexRaw(raw, merged);
            case GEMINI -> mergeGeminiRaw(raw, merged);
            case OPENCODE -> mergeOpenCodeRaw(raw, merged);
        }

        return merged;
    }

    private void mergeClaudeRaw(JsonObject raw, JsonObject merged) {
        mergeRootUnknownFields(raw, merged, List.of(
                "env", "effortLevel", "alwaysThinkingEnabled",
                "dangerouslySkipPermissions", "skipDangerousModePermissionPrompt"));

        JsonObject mergedEnv = merged.has("env") && merged.get("env").isJsonObject()
                ? merged.getAsJsonObject("env")
                : new JsonObject();
        JsonObject rawEnv = raw.has("env") && raw.get("env").isJsonObject()
                ? raw.getAsJsonObject("env")
                : null;
        mergeObjectUnknownFields(rawEnv, mergedEnv, List.of(
                "ANTHROPIC_AUTH_TOKEN",
                "ANTHROPIC_API_KEY",
                "ANTHROPIC_BASE_URL",
                "ANTHROPIC_MODEL",
                "ANTHROPIC_DEFAULT_HAIKU_MODEL",
                "ANTHROPIC_DEFAULT_SONNET_MODEL",
                "ANTHROPIC_DEFAULT_OPUS_MODEL",
                "CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS"));
        merged.add("env", mergedEnv);
    }

    private void mergeCodexRaw(JsonObject raw, JsonObject merged) {
        mergeRootUnknownFields(raw, merged, List.of("auth", "config"));

        JsonObject mergedAuth = merged.has("auth") && merged.get("auth").isJsonObject()
                ? merged.getAsJsonObject("auth")
                : new JsonObject();
        JsonObject rawAuth = raw.has("auth") && raw.get("auth").isJsonObject()
                ? raw.getAsJsonObject("auth")
                : null;
        mergeObjectUnknownFields(rawAuth, mergedAuth, List.of("OPENAI_API_KEY"));
        merged.add("auth", mergedAuth);
    }

    private void mergeGeminiRaw(JsonObject raw, JsonObject merged) {
        mergeRootUnknownFields(raw, merged, List.of("env"));

        JsonObject mergedEnv = merged.has("env") && merged.get("env").isJsonObject()
                ? merged.getAsJsonObject("env")
                : new JsonObject();
        JsonObject rawEnv = raw.has("env") && raw.get("env").isJsonObject()
                ? raw.getAsJsonObject("env")
                : null;
        mergeObjectUnknownFields(rawEnv, mergedEnv, List.of(
                "GEMINI_API_KEY",
                "GOOGLE_GEMINI_BASE_URL",
                "GEMINI_MODEL"));
        merged.add("env", mergedEnv);
    }

    private void mergeOpenCodeRaw(JsonObject raw, JsonObject merged) {
        mergeRootUnknownFields(raw, merged, List.of("npm", "options", "models"));

        JsonObject mergedOptions = merged.has("options") && merged.get("options").isJsonObject()
                ? merged.getAsJsonObject("options")
                : new JsonObject();
        JsonObject rawOptions = raw.has("options") && raw.get("options").isJsonObject()
                ? raw.getAsJsonObject("options")
                : null;
        mergeObjectUnknownFields(rawOptions, mergedOptions, List.of("apiKey", "baseURL"));
        merged.add("options", mergedOptions);

        JsonObject mergedModels = merged.has("models") && merged.get("models").isJsonObject()
                ? merged.getAsJsonObject("models")
                : new JsonObject();
        JsonObject rawModels = raw.has("models") && raw.get("models").isJsonObject()
                ? raw.getAsJsonObject("models")
                : null;
        if (rawModels != null) {
            for (String modelKey : rawModels.keySet()) {
                if (!mergedModels.has(modelKey)) {
                    mergedModels.add(modelKey, rawModels.get(modelKey).deepCopy());
                } else if (rawModels.get(modelKey).isJsonObject() && mergedModels.get(modelKey).isJsonObject()) {
                    JsonObject rawModelDef = rawModels.getAsJsonObject(modelKey);
                    JsonObject mergedModelDef = mergedModels.getAsJsonObject(modelKey);
                    mergeObjectUnknownFields(rawModelDef, mergedModelDef, List.of("name", "options"));

                    JsonObject mergedModelOptions = mergedModelDef.has("options") && mergedModelDef.get("options").isJsonObject()
                            ? mergedModelDef.getAsJsonObject("options")
                            : new JsonObject();
                    JsonObject rawModelOptions = rawModelDef.has("options") && rawModelDef.get("options").isJsonObject()
                            ? rawModelDef.getAsJsonObject("options")
                            : null;
                    mergeObjectUnknownFields(rawModelOptions, mergedModelOptions, List.of("reasoningEffort"));
                    if (rawModelOptions != null || !mergedModelOptions.keySet().isEmpty()) {
                        mergedModelDef.add("options", mergedModelOptions);
                    }
                }
            }
        }
        if (!mergedModels.keySet().isEmpty()) {
            merged.add("models", mergedModels);
        }
    }

    private void mergeRootUnknownFields(JsonObject raw, JsonObject merged, List<String> knownKeys) {
        mergeObjectUnknownFields(raw, merged, knownKeys);
    }

    private void mergeObjectUnknownFields(JsonObject raw, JsonObject merged, List<String> knownKeys) {
        if (raw == null) {
            return;
        }
        for (String key : raw.keySet()) {
            if (!knownKeys.contains(key) && !merged.has(key)) {
                merged.add(key, raw.get(key).deepCopy());
            }
        }
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

        CliType cli = (CliType) cliTypeCombo.getSelectedItem();
        if (cli == null) {
            return null;
        }
        if (getCurrentAuthMode(cli) == AuthMode.OFFICIAL_LOGIN) {
            return null;
        }
        return switch (cli) {
            case CLAUDE -> validateClaude();
            case CODEX -> validateCodex();
            case GEMINI -> validateGemini();
            case OPENCODE -> validateOpenCode();
        };
    }

    private ValidationInfo validateClaude() {
        if (claudeApiKey.getText().isBlank()) {
            return new ValidationInfo(I18n.t("providerDialog.validate.apiKeyRequired"), claudeApiKey);
        }
        if (claudeBaseUrl.getText().isBlank()) {
            return new ValidationInfo(I18n.t("providerDialog.validate.baseUrlRequired"), claudeBaseUrl);
        }
        if (claudeModel.getText().isBlank()) {
            return new ValidationInfo(I18n.t("providerDialog.validate.modelRequired"), claudeModel);
        }
        return null;
    }

    private ValidationInfo validateCodex() {
        if (codexApiKey.getText().isBlank()) {
            return new ValidationInfo(I18n.t("providerDialog.validate.apiKeyRequired"), codexApiKey);
        }
        if (codexBaseUrl.getText().isBlank()) {
            return new ValidationInfo(I18n.t("providerDialog.validate.baseUrlRequired"), codexBaseUrl);
        }
        if (codexModel.getText().isBlank()) {
            return new ValidationInfo(I18n.t("providerDialog.validate.modelRequired"), codexModel);
        }
        return null;
    }

    private ValidationInfo validateGemini() {
        if (geminiApiKey.getText().isBlank()) {
            return new ValidationInfo(I18n.t("providerDialog.validate.apiKeyRequired"), geminiApiKey);
        }
        if (geminiBaseUrl.getText().isBlank()) {
            return new ValidationInfo(I18n.t("providerDialog.validate.baseUrlRequired"), geminiBaseUrl);
        }
        if (geminiModel.getText().isBlank()) {
            return new ValidationInfo(I18n.t("providerDialog.validate.modelRequired"), geminiModel);
        }
        return null;
    }

    private ValidationInfo validateOpenCode() {
        if (opencodeApiKey.getText().isBlank()) {
            return new ValidationInfo(I18n.t("providerDialog.validate.apiKeyRequired"), opencodeApiKey);
        }
        if (opencodeBaseUrl.getText().isBlank()) {
            return new ValidationInfo(I18n.t("providerDialog.validate.baseUrlRequired"), opencodeBaseUrl);
        }
        // OpenCode 的模型是动态列表，检查是否至少有一个模型
        boolean hasModel = opencodeModelRows.stream()
                .anyMatch(row -> !row.nameField.getText().isBlank());
        if (!hasModel) {
            return new ValidationInfo(I18n.t("providerDialog.validate.modelRequired"),
                    opencodeModelRows.isEmpty() ? opencodeModelsPanel : opencodeModelRows.get(0).nameField);
        }
        return null;
    }

    public Provider getProvider() {
        CliType cliType = (CliType) cliTypeCombo.getSelectedItem();
        provider.setCliType(cliType);
        provider.setName(nameField.getText().trim());
        provider.setAuthMode(getCurrentAuthMode(cliType));
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
        if (getCurrentAuthMode(cliType) == AuthMode.OFFICIAL_LOGIN) {
            Messages.showInfoMessage(
                    I18n.t("providerDialog.test.skipOfficialLogin"),
                    I18n.t("providerDialog.test.skipOfficialLoginTitle"));
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
        testStatusLabel.setToolTipText(null);
        testStatusLabel.setForeground(JBColor.GRAY);

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            ProviderConnectionTestService.TestResult result = ProviderConnectionTestService.getInstance().test(cliType, config);
            ApplicationManager.getApplication().invokeLater(() -> {
                testConnectionButton.setEnabled(true);
                if (result.success()) {
                    String successMessage = I18n.t("providerDialog.test.success", result.durationMs());
                    testStatusLabel.setText(successMessage);
                    testStatusLabel.setToolTipText(null);
                    testStatusLabel.setForeground(new JBColor(new Color(66, 160, 83), new Color(66, 160, 83)));
                } else {
                    String failureMessage = I18n.t("providerDialog.test.failed", result.message());
                    testStatusLabel.setText(abbreviateTestStatus(failureMessage));
                    testStatusLabel.setToolTipText(failureMessage);
                    testStatusLabel.setForeground(JBColor.RED);
                }
            }, ModalityState.any());
        });
    }

    private void openConfigDir() {
        CliType cliType = (CliType) cliTypeCombo.getSelectedItem();
        if (cliType == null) {
            return;
        }

        ConfigFileService svc = ConfigFileService.getInstance();
        java.nio.file.Path configDir = svc.getConfigDir(cliType);

        if (!java.nio.file.Files.isDirectory(configDir)) {
            Messages.showInfoMessage(
                    I18n.t("providerDialog.openDir.notInstalled", cliType.getDisplayName()),
                    I18n.t("providerDialog.openDir.title"));
            return;
        }

        // 获取要选中的配置文件路径
        java.nio.file.Path configFile = getTargetConfigFile(cliType, svc);

        try {
            // 优先尝试打开并选中文件
            if (configFile != null && java.nio.file.Files.exists(configFile)) {
                openAndSelectFile(configFile);
            } else {
                // 文件不存在则只打开目录
                java.awt.Desktop.getDesktop().open(configDir.toFile());
            }
        } catch (Exception e) {
            Messages.showErrorDialog(
                    I18n.t("providerDialog.openDir.failed", e.getMessage()),
                    I18n.t("providerDialog.openDir.title"));
        }
    }

    private java.nio.file.Path getTargetConfigFile(CliType cliType, ConfigFileService svc) {
        return switch (cliType) {
            case CLAUDE -> svc.getConfigDir(cliType).resolve("settings.json");
            case CODEX -> svc.getConfigDir(cliType).resolve("config.toml");
            case GEMINI -> svc.getConfigDir(cliType).resolve(".env");
            case OPENCODE -> svc.getConfigDir(cliType).resolve("opencode.json");
        };
    }

    private void openAndSelectFile(java.nio.file.Path file) throws Exception {
        String os = System.getProperty("os.name").toLowerCase();

        if (os.contains("win")) {
            // Windows: explorer /select,<path>
            Runtime.getRuntime().exec(new String[]{"explorer", "/select,", file.toString()});
        } else if (os.contains("mac")) {
            // macOS: open -R <path>
            Runtime.getRuntime().exec(new String[]{"open", "-R", file.toString()});
        } else {
            // Linux: 尝试使用 dbus 打开目录（无法选中文件）
            java.awt.Desktop.getDesktop().open(file.getParent().toFile());
        }
    }
}
