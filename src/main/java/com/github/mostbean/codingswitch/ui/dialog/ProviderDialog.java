package com.github.mostbean.codingswitch.ui.dialog;

import com.github.mostbean.codingswitch.model.CliType;
import com.github.mostbean.codingswitch.model.Provider;
import com.github.mostbean.codingswitch.service.ProviderPresets;
import com.google.gson.JsonObject;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.List;

/**
 * Provider 编辑对话框。
 * 顶部预设平铺按钮，选择后自动填充下方字段。
 */
public class ProviderDialog extends DialogWrapper {

    private static final String PRESET_NONE = "自定义";
    private static final String OFFICIAL_HINT = "💡 无需配置 API Key，激活后首次运行 CLI 将自动打开浏览器完成官方登录";

    private final JPanel presetButtonsPanel = new JPanel(new GridLayout(0, 5, 8, 8));
    private final JBLabel presetHintLabel = new JBLabel(" ");

    private final JComboBox<CliType> cliTypeCombo = new JComboBox<>(CliType.values());
    private final JTextField nameField = new JTextField(30);

    // Claude 字段
    private final JTextField claudeApiKey = new JTextField(30);
    private final JTextField claudeBaseUrl = new JTextField(30);
    private final JTextField claudeModel = new JTextField(30);
    private final JTextField claudeHaiku = new JTextField(30);
    private final JTextField claudeSonnet = new JTextField(30);
    private final JTextField claudeOpus = new JTextField(30);
    private final JComboBox<String> claudeApiKeyField = new JComboBox<>(
            new String[] { "ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_API_KEY" });

    // Codex 字段
    private final JTextField codexApiKey = new JTextField(30);
    private final JTextField codexBaseUrl = new JTextField(30);
    private final JTextField codexModel = new JTextField(30);
    private final JComboBox<String> codexReasoningEffort = new JComboBox<>(
            new String[] { "xhigh", "high", "medium", "low" });
    private static final String CODEX_PROVIDER_SLUG = "custom";

    // Gemini 字段
    private final JTextField geminiApiKey = new JTextField(30);
    private final JTextField geminiBaseUrl = new JTextField(30);
    private final JTextField geminiModel = new JTextField(30);

    // OpenCode 字段
    private final JTextField opencodeApiKey = new JTextField(30);
    private final JTextField opencodeBaseUrl = new JTextField(30);
    private final JTextField opencodeModel = new JTextField(30);
    private final JComboBox<String> opencodeNpm = createEditableCombo(
            "@ai-sdk/openai-compatible",
            "@ai-sdk/openai",
            "@ai-sdk/anthropic",
            "@ai-sdk/google",
            "@ai-sdk/google-vertex",
            "@ai-sdk/azure",
            "@ai-sdk/amazon-bedrock",
            "@ai-sdk/mistral");

    private final JPanel dynamicPanel = new JPanel(new CardLayout());
    private final Provider provider;
    private String selectedPreset = PRESET_NONE;
    private List<ProviderPresets.Preset> currentPresets = List.of();

    public ProviderDialog(@Nullable Provider existing) {
        super(true);
        this.provider = existing != null ? existing : new Provider();

        setTitle(existing != null ? "编辑配置" : "新增配置");

        // 构建 CLI 面板
        dynamicPanel.add(buildClaudePanel(), CliType.CLAUDE.name());
        dynamicPanel.add(buildCodexPanel(), CliType.CODEX.name());
        dynamicPanel.add(buildGeminiPanel(), CliType.GEMINI.name());
        dynamicPanel.add(buildOpenCodePanel(), CliType.OPENCODE.name());

        // CLI 类型切换 → 更新面板 + 刷新预设按钮
        cliTypeCombo.addActionListener(e -> {
            CliType selected = (CliType) cliTypeCombo.getSelectedItem();
            if (selected != null) {
                ((CardLayout) dynamicPanel.getLayout()).show(dynamicPanel, selected.name());
                refreshPresetButtons(selected);
            }
        });

        // 加载已有数据
        if (existing != null) {
            cliTypeCombo.setSelectedItem(existing.getCliType());
            nameField.setText(existing.getName());
            loadSettingsConfig(existing.getCliType(), existing.getSettingsConfig());
        } else {
            opencodeNpm.setSelectedItem("@ai-sdk/openai-compatible");
        }

        init();

        // 触发初始面板 + 预设按钮
        CliType initial = (CliType) cliTypeCombo.getSelectedItem();
        if (initial != null) {
            ((CardLayout) dynamicPanel.getLayout()).show(dynamicPanel, initial.name());
            refreshPresetButtons(initial);
        }
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel centerPanel = new JPanel(new BorderLayout(0, JBUI.scale(8)));
        centerPanel.setBorder(JBUI.Borders.empty(4, 0));

        // 预设提示标签样式
        presetHintLabel.setForeground(new JBColor(new Color(200, 130, 0), new Color(230, 180, 80)));
        presetHintLabel.setBorder(JBUI.Borders.empty(0, 0, 4, 0));

        // 顶部：预设按钮 + 基本信息
        JPanel topPanel = FormBuilder.createFormBuilder()
                .addLabeledComponent("预设配置:", presetButtonsPanel)
                .addComponent(presetHintLabel)
                .addSeparator(8)
                .addLabeledComponent("CLI 类型:", cliTypeCombo)
                .addLabeledComponent("配置名称:", nameField)
                .getPanel();

        centerPanel.add(topPanel, BorderLayout.NORTH);
        centerPanel.add(dynamicPanel, BorderLayout.CENTER);

        return centerPanel;
    }

    // =====================================================================
    // 预设按钮逻辑
    // =====================================================================

    private void refreshPresetButtons(CliType cliType) {
        presetButtonsPanel.removeAll();
        currentPresets = ProviderPresets.forCli(cliType);

        // 自定义按钮
        JButton customBtn = createPresetButton(PRESET_NONE, true);
        presetButtonsPanel.add(customBtn);

        // 预设按钮
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

        // 统一按钮尺寸和边距
        btn.setMargin(JBUI.insets(6, 12));
        btn.setPreferredSize(new Dimension(JBUI.scale(100), JBUI.scale(32)));

        return btn;
    }

    private void onPresetSelected(String presetName) {
        selectedPreset = presetName;
        updatePresetButtonStyles();

        if (PRESET_NONE.equals(presetName)) {
            presetHintLabel.setText(" ");
            return;
        }

        CliType cliType = (CliType) cliTypeCombo.getSelectedItem();
        for (ProviderPresets.Preset preset : currentPresets) {
            if (preset.name().equals(presetName)) {
                nameField.setText(preset.name());
                clearAllFields(cliType);
                loadSettingsConfig(cliType, preset.settingsConfig());

                // Official 预设给出提示
                if ("Official Login".equals(preset.name())) {
                    presetHintLabel.setText(OFFICIAL_HINT);
                } else {
                    presetHintLabel.setText("💡 已填充预设，请补充 API Key 后保存");
                }
                return;
            }
        }
    }

    private void updatePresetButtonStyles() {
        for (Component comp : presetButtonsPanel.getComponents()) {
            if (comp instanceof JButton btn) {
                String presetName = (String) btn.getClientProperty("presetName");
                if (selectedPreset.equals(presetName)) {
                    // 选中状态：蓝色边框高亮，无背景色变化
                    btn.setBorder(BorderFactory.createCompoundBorder(
                            BorderFactory.createLineBorder(new JBColor(new Color(59, 130, 246), new Color(96, 165, 250)), 2),
                            BorderFactory.createEmptyBorder(4, 10, 4, 10)));
                    btn.setFont(btn.getFont().deriveFont(Font.BOLD));
                } else {
                    // 未选中状态：默认边框
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
                opencodeModel.setText("");
            }
        }
    }

    // =====================================================================
    // FormBuilder 子面板（中文标签）
    // =====================================================================

    private JPanel buildClaudePanel() {
        JPanel form = FormBuilder.createFormBuilder()
                .addLabeledComponent("Key 字段名:", claudeApiKeyField)
                .addLabeledComponent("API Key:", claudeApiKey)
                .addLabeledComponent("Base URL:", claudeBaseUrl)
                .addSeparator(8)
                .addLabeledComponent("主模型:", claudeModel)
                .addLabeledComponent("Haiku 模型:", claudeHaiku)
                .addLabeledComponent("Sonnet 模型:", claudeSonnet)
                .addLabeledComponent("Opus 模型:", claudeOpus)
                .getPanel();
        return wrapWithTitledBorder(form, "Claude Code 配置");
    }

    private JPanel buildCodexPanel() {
        JPanel form = FormBuilder.createFormBuilder()
                .addLabeledComponent("API Key:", codexApiKey)
                .addLabeledComponent("Base URL:", codexBaseUrl)
                .addLabeledComponent("模型:", codexModel)
                .addSeparator(8)
                .addLabeledComponent("推理强度:", codexReasoningEffort)
                .getPanel();
        return wrapWithTitledBorder(form, "Codex 配置");
    }

    private JPanel buildGeminiPanel() {
        JPanel form = FormBuilder.createFormBuilder()
                .addLabeledComponent("API Key:", geminiApiKey)
                .addLabeledComponent("Base URL:", geminiBaseUrl)
                .addLabeledComponent("模型:", geminiModel)
                .getPanel();
        return wrapWithTitledBorder(form, "Gemini CLI 配置");
    }

    private JPanel buildOpenCodePanel() {
        JPanel form = FormBuilder.createFormBuilder()
                .addLabeledComponent("NPM 包:", opencodeNpm)
                .addLabeledComponent("API Key:", opencodeApiKey)
                .addLabeledComponent("Base URL:", opencodeBaseUrl)
                .addLabeledComponent("模型:", opencodeModel)
                .getPanel();
        return wrapWithTitledBorder(form, "OpenCode 配置");
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
    // settingsConfig 读取与构建（逻辑不变）
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
        if (!config.has("env"))
            return;
        JsonObject env = config.getAsJsonObject("env");
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
            if (!models.keySet().isEmpty()) {
                opencodeModel.setText(models.keySet().iterator().next());
            }
        }
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
        config.add("env", env);
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

        String model = opencodeModel.getText().trim();
        if (!model.isEmpty()) {
            JsonObject models = new JsonObject();
            JsonObject modelDef = new JsonObject();
            modelDef.addProperty("name", model);
            models.add(model, modelDef);
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
            return new ValidationInfo("请填写配置名称", nameField);
        }
        // Official 预设不需要校验 API Key
        if ("Official Login".equals(selectedPreset))
            return null;

        CliType cli = (CliType) cliTypeCombo.getSelectedItem();
        return switch (cli) {
            case CLAUDE -> claudeApiKey.getText().isBlank()
                    ? new ValidationInfo("请填写 API Key", claudeApiKey)
                    : null;
            case CODEX -> codexApiKey.getText().isBlank()
                    ? new ValidationInfo("请填写 API Key", codexApiKey)
                    : null;
            case GEMINI -> geminiApiKey.getText().isBlank()
                    ? new ValidationInfo("请填写 API Key", geminiApiKey)
                    : null;
            case OPENCODE -> opencodeApiKey.getText().isBlank()
                    ? new ValidationInfo("请填写 API Key", opencodeApiKey)
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
}
