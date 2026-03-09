package com.github.mostbean.codingswitch.ui.dialog;

import com.github.mostbean.codingswitch.model.Provider;
import com.github.mostbean.codingswitch.service.OpenCodeConfigService;
import com.github.mostbean.codingswitch.service.OpenCodeOmoMetadata;
import com.github.mostbean.codingswitch.service.ProviderService;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.text.JTextComponent;
import java.awt.CardLayout;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Window;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class OpenCodeProviderEditorPanel {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String DEFAULT_NPM = "@ai-sdk/openai-compatible";
    private static final String MODE_CUSTOM = "custom";
    private static final String MODE_OMO = "omo";

    private final List<Runnable> changeListeners = new ArrayList<>();
    private final JPanel rootPanel = new JPanel(new BorderLayout(0, JBUI.scale(8)));
    private final JPanel modePanel = new JPanel(new CardLayout());

    private final JComboBox<CategoryItem> categoryCombo = new JComboBox<>(new CategoryItem[] {
            new CategoryItem(Provider.CATEGORY_CUSTOM, "普通 OpenCode"),
            new CategoryItem(Provider.CATEGORY_OMO, "Oh My OpenCode"),
            new CategoryItem(Provider.CATEGORY_OMO_SLIM, "Oh My OpenCode Slim")
    });

    private final JTextField providerKeyField = new JTextField(24);
    private final JComboBox<String> npmCombo = new JComboBox<>(new String[] {
            "@ai-sdk/openai-compatible",
            "@ai-sdk/openai",
            "@ai-sdk/anthropic",
            "@ai-sdk/google",
            "@ai-sdk/amazon-bedrock"
    });
    private final JTextField apiKeyField = new JTextField(24);
    private final JTextField baseUrlField = new JTextField(24);
    private final JPanel extraOptionsPanel = new JPanel();
    private final List<ExtraOptionRow> extraOptionRows = new ArrayList<>();
    private final JPanel modelsPanel = new JPanel();
    private final List<ModelRow> modelRows = new ArrayList<>();

    private final JBLabel modelSourceStatusLabel = new JBLabel(" ");
    private final JPanel mainAgentsPanel = new JPanel();
    private final JPanel subAgentsPanel = new JPanel();
    private final JPanel categoriesPanel = new JPanel();
    private final JPanel customAgentsPanel = new JPanel();
    private final JPanel customCategoriesPanel = new JPanel();
    private final JTextArea otherFieldsArea = createJsonArea(6);

    private final LinkedHashMap<String, JsonObject> omoAgents = new LinkedHashMap<>();
    private final LinkedHashMap<String, JsonObject> omoCategories = new LinkedHashMap<>();
    private final List<String> customAgentKeys = new ArrayList<>();
    private final List<String> customCategoryKeys = new ArrayList<>();

    private final LinkedHashMap<String, ModelChoice> modelChoices = new LinkedHashMap<>();
    private final Map<String, List<String>> variantChoices = new LinkedHashMap<>();

    private final String editingProviderId;
    private final boolean editingExistingProvider;
    private JsonObject rawConfig = new JsonObject();
    private boolean updatingCategory;
    private boolean providerKeyTouched;

    public OpenCodeProviderEditorPanel(@Nullable Provider existing) {
        this.editingProviderId = existing != null ? existing.getId() : null;
        this.editingExistingProvider = existing != null;

        npmCombo.setEditable(true);
        extraOptionsPanel.setLayout(new BoxLayout(extraOptionsPanel, BoxLayout.Y_AXIS));
        modelsPanel.setLayout(new BoxLayout(modelsPanel, BoxLayout.Y_AXIS));
        mainAgentsPanel.setLayout(new BoxLayout(mainAgentsPanel, BoxLayout.Y_AXIS));
        subAgentsPanel.setLayout(new BoxLayout(subAgentsPanel, BoxLayout.Y_AXIS));
        categoriesPanel.setLayout(new BoxLayout(categoriesPanel, BoxLayout.Y_AXIS));
        customAgentsPanel.setLayout(new BoxLayout(customAgentsPanel, BoxLayout.Y_AXIS));
        customCategoriesPanel.setLayout(new BoxLayout(customCategoriesPanel, BoxLayout.Y_AXIS));

        rootPanel.add(buildHeaderPanel(), BorderLayout.NORTH);
        modePanel.add(buildCustomPanel(), MODE_CUSTOM);
        modePanel.add(buildOmoPanel(), MODE_OMO);
        rootPanel.add(modePanel, BorderLayout.CENTER);

        categoryCombo.addActionListener(e -> {
            if (updatingCategory) {
                return;
            }
            CategoryItem item = (CategoryItem) categoryCombo.getSelectedItem();
            switchCategory(item != null ? item.value() : Provider.CATEGORY_CUSTOM, true);
        });

        addDocumentListener(providerKeyField, () -> providerKeyTouched = true);
        addDocumentListener(apiKeyField);
        addDocumentListener(baseUrlField);
        addDocumentListener(otherFieldsArea);
        npmCombo.addActionListener(e -> notifyChanged());

        if (existing != null) {
            loadProvider(existing);
        } else {
            clear();
        }
    }

    public JComponent getComponent() {
        return rootPanel;
    }

    public void addChangeListener(Runnable listener) {
        changeListeners.add(listener);
    }

    public void loadProvider(Provider provider) {
        loadSettingsConfig(provider != null ? provider.getNormalizedCategory() : Provider.CATEGORY_CUSTOM,
                provider != null ? provider.getSettingsConfig() : new JsonObject(),
                provider != null ? provider.getProviderKey() : null);
    }

    public void loadSettingsConfig(String category, @Nullable JsonObject config, @Nullable String providerKey) {
        rawConfig = config != null ? config.deepCopy() : new JsonObject();
        switchCategory(category, false);

        if (Provider.CATEGORY_CUSTOM.equals(getCategory())) {
            loadCustomConfig(rawConfig, providerKey);
        } else {
            loadOmoConfig(rawConfig);
        }
        notifyChanged();
    }

    public void clear() {
        rawConfig = new JsonObject();
        switchCategory(Provider.CATEGORY_CUSTOM, false);
        loadCustomConfig(new JsonObject(), null);
        clearOmoState();
        rebuildOmoPanels();
        notifyChanged();
    }

    public String getCategory() {
        CategoryItem item = (CategoryItem) categoryCombo.getSelectedItem();
        return item != null ? item.value() : Provider.CATEGORY_CUSTOM;
    }

    public void setCategory(String category) {
        switchCategory(category, true);
    }

    public void onProviderNameChanged(String providerName) {
        if (providerKeyTouched || !Provider.CATEGORY_CUSTOM.equals(getCategory()) || !providerKeyField.isEditable()) {
            return;
        }
        String suggested = ProviderService.slugifyProviderKey(providerName);
        providerKeyField.setText(suggested != null ? suggested : "");
    }

    public String getProviderKey() {
        return Provider.CATEGORY_CUSTOM.equals(getCategory())
                ? providerKeyField.getText().trim()
                : null;
    }

    public boolean supportsConnectionTest() {
        return Provider.CATEGORY_CUSTOM.equals(getCategory());
    }

    public boolean isPreviewEditable() {
        return Provider.CATEGORY_CUSTOM.equals(getCategory());
    }

    public ValidationInfo validate() {
        if (Provider.CATEGORY_CUSTOM.equals(getCategory())) {
            String providerKey = providerKeyField.getText().trim();
            if (providerKey.isBlank()) {
                return new ValidationInfo("providerKey 不能为空", providerKeyField);
            }
            String normalized = ProviderService.slugifyProviderKey(providerKey);
            if (!providerKey.equals(normalized)) {
                return new ValidationInfo("providerKey 只能使用小写字母、数字和中划线", providerKeyField);
            }
            if (!ProviderService.getInstance().isOpenCodeProviderKeyAvailable(providerKey, editingProviderId)) {
                return new ValidationInfo("providerKey 已存在", providerKeyField);
            }
            for (ModelRow row : modelRows) {
                String modelId = row.modelIdField().getText().trim();
                if (modelId.isBlank()) {
                    continue;
                }
                String optionsJson = row.optionsField().getText().trim();
                if (!optionsJson.isBlank() && parseJsonObject(optionsJson) == null) {
                    return new ValidationInfo("模型 options 必须是 JSON 对象", row.optionsField());
                }
            }
            return null;
        }

        if (!otherFieldsArea.getText().trim().isBlank() && parseJsonObject(otherFieldsArea.getText()) == null) {
            return new ValidationInfo("Other Fields 必须是 JSON 对象", otherFieldsArea);
        }
        for (String key : customAgentKeys) {
            if (key == null || key.isBlank()) {
                return new ValidationInfo("自定义 Agent key 不能为空");
            }
        }
        if (Provider.CATEGORY_OMO.equals(getCategory())) {
            for (String key : customCategoryKeys) {
                if (key == null || key.isBlank()) {
                    return new ValidationInfo("自定义 Category key 不能为空");
                }
            }
        }
        return null;
    }

    public JsonObject buildSettingsConfig() {
        return Provider.CATEGORY_CUSTOM.equals(getCategory())
                ? buildCustomConfig()
                : buildOmoDraftConfig();
    }

    public String formatPreviewContent(JsonObject config) {
        if (Provider.CATEGORY_CUSTOM.equals(getCategory())) {
            JsonObject providerWrapper = new JsonObject();
            String key = getProviderKey().isBlank() ? "provider-key" : getProviderKey();
            providerWrapper.add(key, config != null ? config.deepCopy() : new JsonObject());
            JsonObject preview = new JsonObject();
            preview.add("provider", providerWrapper);
            return "// opencode.json -> provider\n" + GSON.toJson(preview);
        }
        JsonObject merged = OpenCodeConfigService.getInstance().buildMergedOmoConfig(getCategory(), config);
        return GSON.toJson(merged);
    }

    public void applyPreviewToForm(String previewText) {
        if (!Provider.CATEGORY_CUSTOM.equals(getCategory())) {
            return;
        }

        JsonObject parsed = parseProviderPreview(previewText);
        if (parsed == null) {
            throw new IllegalArgumentException("OpenCode 预览 JSON 无法解析");
        }
        rawConfig = parsed.deepCopy();
        loadCustomConfig(parsed, getProviderKey());
        notifyChanged();
    }

    private JPanel buildHeaderPanel() {
        return FormBuilder.createFormBuilder()
                .addLabeledComponent("OpenCode 类型", categoryCombo)
                .getPanel();
    }

    private JPanel buildCustomPanel() {
        JButton addOptionButton = new JButton("添加 Option");
        addOptionButton.addActionListener(e -> {
            addExtraOptionRow("", "");
            notifyChanged();
        });

        JButton addModelButton = new JButton("添加模型");
        addModelButton.addActionListener(e -> {
            addModelRow("", "", "");
            notifyChanged();
        });

        JPanel extraOptionsWrapper = new JPanel(new BorderLayout(0, 4));
        extraOptionsWrapper.add(createColumnHeader("键名", "值"), BorderLayout.NORTH);
        extraOptionsWrapper.add(extraOptionsPanel, BorderLayout.CENTER);
        extraOptionsWrapper.add(addOptionButton, BorderLayout.SOUTH);

        JPanel modelsWrapper = new JPanel(new BorderLayout(0, 4));
        modelsWrapper.add(createColumnHeader("模型 ID", "显示名称"), BorderLayout.NORTH);
        modelsWrapper.add(modelsPanel, BorderLayout.CENTER);
        modelsWrapper.add(addModelButton, BorderLayout.SOUTH);

        JPanel providerKeyWrapper = wrapWithHint(providerKeyField,
                "live opencode.json 中 provider 的唯一 key。创建后将作为同步 key 使用。");
        JPanel npmWrapper = wrapWithHint(npmCombo,
                "选择与当前 provider 对应的 AI SDK 包。");
        JPanel baseUrlWrapper = wrapWithHint(baseUrlField,
                "接口基础地址，可留空以使用官方 SDK 默认地址。");
        JPanel extraOptionsSection = wrapWithHint(extraOptionsWrapper,
                "配置额外的 SDK 选项，值会优先按 JSON 类型解析。");
        JPanel modelsSection = wrapWithHint(modelsWrapper,
                "模型区保留模型 ID 与显示名称两列；如需补充 options，请点击每行“选项”。");

        return FormBuilder.createFormBuilder()
                .addLabeledComponent("providerKey", providerKeyWrapper)
                .addLabeledComponent("NPM 包", npmWrapper)
                .addLabeledComponent("API Key", apiKeyField)
                .addLabeledComponent("Base URL", baseUrlWrapper)
                .addLabeledComponent("Extra Options", extraOptionsSection)
                .addLabeledComponent("模型", modelsSection)
                .getPanel();
    }

    private JPanel wrapWithHint(JComponent component, String hint) {
        JPanel wrapper = new JPanel(new BorderLayout(0, 4));
        wrapper.add(component, BorderLayout.CENTER);
        if (hint != null && !hint.isBlank()) {
            JBLabel hintLabel = new JBLabel(hint);
            hintLabel.setForeground(JBUI.CurrentTheme.ContextHelp.FOREGROUND);
            wrapper.add(hintLabel, BorderLayout.SOUTH);
        }
        return wrapper;
    }

    private JPanel createColumnHeader(String first, String second) {
        JPanel header = new JPanel(new BorderLayout(4, 0));
        JPanel center = new JPanel(new GridLayout(1, 2, 4, 0));
        JBLabel firstLabel = new JBLabel(first);
        JBLabel secondLabel = new JBLabel(second);
        firstLabel.setForeground(JBUI.CurrentTheme.ContextHelp.FOREGROUND);
        secondLabel.setForeground(JBUI.CurrentTheme.ContextHelp.FOREGROUND);
        center.add(firstLabel);
        center.add(secondLabel);
        header.add(center, BorderLayout.CENTER);
        header.add(Box.createHorizontalStrut(JBUI.scale(128)), BorderLayout.EAST);
        return header;
    }

    private JTextArea createJsonArea(int rows) {
        JTextArea area = new JTextArea(rows, 40);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, area.getFont().getSize()));
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        return area;
    }

    private void loadCustomConfig(JsonObject config, @Nullable String providerKey) {
        providerKeyTouched = false;
        providerKeyField.setEditable(!editingExistingProvider || providerKey == null || providerKey.isBlank());
        providerKeyField.setText(providerKey != null ? providerKey : "");

        npmCombo.setSelectedItem(config.has("npm") ? config.get("npm").getAsString() : DEFAULT_NPM);
        JsonObject options = config.has("options") && config.get("options").isJsonObject()
                ? config.getAsJsonObject("options")
                : new JsonObject();
        apiKeyField.setText(options.has("apiKey") && !options.get("apiKey").isJsonNull()
                ? options.get("apiKey").getAsString()
                : "");
        baseUrlField.setText(options.has("baseURL") && !options.get("baseURL").isJsonNull()
                ? options.get("baseURL").getAsString()
                : "");

        clearExtraOptionRows();
        for (String key : options.keySet()) {
            if (!"apiKey".equals(key) && !"baseURL".equals(key)) {
                addExtraOptionRow(key, stringifyValue(options.get(key)));
            }
        }
        if (extraOptionRows.isEmpty()) {
            addExtraOptionRow("", "");
        }

        clearModelRows();
        JsonObject models = config.has("models") && config.get("models").isJsonObject()
                ? config.getAsJsonObject("models")
                : new JsonObject();
        for (String modelId : models.keySet()) {
            JsonObject modelConfig = models.get(modelId).isJsonObject()
                    ? models.getAsJsonObject(modelId)
                    : new JsonObject();
            String displayName = modelConfig.has("name") && !modelConfig.get("name").isJsonNull()
                    ? modelConfig.get("name").getAsString()
                    : modelId;
            String optionsJson = modelConfig.has("options") && modelConfig.get("options").isJsonObject()
                    ? GSON.toJson(modelConfig.getAsJsonObject("options"))
                    : "";
            addModelRow(modelId, displayName, optionsJson);
        }
        if (modelRows.isEmpty()) {
            addModelRow("", "", "");
        }
    }

    private void clearExtraOptionRows() {
        extraOptionRows.clear();
        extraOptionsPanel.removeAll();
        extraOptionsPanel.revalidate();
        extraOptionsPanel.repaint();
    }

    private void addExtraOptionRow(String key, String value) {
        JTextField keyField = new JTextField(key, 12);
        JTextField valueField = new JTextField(value, 20);
        JButton removeButton = new JButton("移除");
        JPanel rowPanel = new JPanel(new BorderLayout(4, 0));
        JPanel center = new JPanel(new GridLayout(1, 2, 4, 0));
        center.add(keyField);
        center.add(valueField);
        rowPanel.add(center, BorderLayout.CENTER);
        rowPanel.add(removeButton, BorderLayout.EAST);

        ExtraOptionRow row = new ExtraOptionRow(keyField, valueField, rowPanel);
        extraOptionRows.add(row);
        extraOptionsPanel.add(rowPanel);
        addDocumentListener(keyField);
        addDocumentListener(valueField);
        removeButton.addActionListener(e -> {
            extraOptionRows.remove(row);
            extraOptionsPanel.remove(rowPanel);
            extraOptionsPanel.revalidate();
            extraOptionsPanel.repaint();
            notifyChanged();
        });
    }

    private void clearModelRows() {
        modelRows.clear();
        modelsPanel.removeAll();
        modelsPanel.revalidate();
        modelsPanel.repaint();
    }

    private void addModelRow(String modelId, String displayName, String optionsJson) {
        JTextField modelIdField = new JTextField(modelId, 12);
        JTextField displayNameField = new JTextField(displayName, 12);
        JTextField optionsField = new JTextField(optionsJson, 18);
        optionsField.setVisible(false);
        optionsField.setEnabled(false);
        JButton optionsButton = new JButton(optionsJson != null && !optionsJson.isBlank() ? "选项 *" : "选项");
        JButton removeButton = new JButton("移除");

        JPanel rowPanel = new JPanel(new BorderLayout(4, 0));
        JPanel center = new JPanel(new GridLayout(1, 2, 4, 0));
        center.add(modelIdField);
        center.add(displayNameField);
        rowPanel.add(center, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        actions.add(optionsButton);
        actions.add(removeButton);
        rowPanel.add(actions, BorderLayout.EAST);

        ModelRow row = new ModelRow(modelIdField, displayNameField, optionsField, rowPanel);
        modelRows.add(row);
        modelsPanel.add(rowPanel);

        addDocumentListener(modelIdField);
        addDocumentListener(displayNameField);
        optionsButton.addActionListener(e -> {
            JsonObject edited = JsonObjectEditorDialog.edit(rootPanel,
                    "模型 Options JSON",
                    optionsField.getText().trim());
            if (edited == null && !optionsField.getText().trim().isBlank()) {
                return;
            }
            String nextText = edited == null || edited.keySet().isEmpty() ? "" : GSON.toJson(edited);
            optionsField.setText(nextText);
            optionsButton.setText(nextText.isBlank() ? "选项" : "选项 *");
            notifyChanged();
        });
        removeButton.addActionListener(e -> {
            modelRows.remove(row);
            modelsPanel.remove(rowPanel);
            modelsPanel.revalidate();
            modelsPanel.repaint();
            notifyChanged();
        });
    }

    private JsonObject buildCustomConfig() {
        JsonObject structured = new JsonObject();
        String npm = Objects.toString(npmCombo.getSelectedItem(), DEFAULT_NPM).trim();
        structured.addProperty("npm", npm.isBlank() ? DEFAULT_NPM : npm);

        JsonObject options = new JsonObject();
        if (!baseUrlField.getText().trim().isBlank()) {
            options.addProperty("baseURL", baseUrlField.getText().trim());
        }
        if (!apiKeyField.getText().trim().isBlank()) {
            options.addProperty("apiKey", apiKeyField.getText().trim());
        }
        for (ExtraOptionRow row : extraOptionRows) {
            String key = row.keyField().getText().trim();
            String value = row.valueField().getText().trim();
            if (!key.isBlank()) {
                options.add(key, parseJsonValue(value));
            }
        }
        structured.add("options", options);

        JsonObject rawModels = rawConfig.has("models") && rawConfig.get("models").isJsonObject()
                ? rawConfig.getAsJsonObject("models")
                : new JsonObject();
        JsonObject models = new JsonObject();
        for (ModelRow row : modelRows) {
            String modelId = row.modelIdField().getText().trim();
            if (modelId.isBlank()) {
                continue;
            }
            JsonObject model = rawModels.has(modelId) && rawModels.get(modelId).isJsonObject()
                    ? rawModels.getAsJsonObject(modelId).deepCopy()
                    : new JsonObject();
            if (!row.displayNameField().getText().trim().isBlank()) {
                model.addProperty("name", row.displayNameField().getText().trim());
            } else {
                model.remove("name");
            }
            JsonObject optionsObject = parseJsonObject(row.optionsField().getText().trim());
            if (optionsObject != null && !optionsObject.keySet().isEmpty()) {
                model.add("options", optionsObject);
            } else {
                model.remove("options");
            }
            models.add(modelId, model);
        }
        if (!models.keySet().isEmpty()) {
            structured.add("models", models);
        }

        JsonObject merged = new JsonObject();
        for (String key : rawConfig.keySet()) {
            if (!"npm".equals(key) && !"options".equals(key) && !"models".equals(key)) {
                merged.add(key, rawConfig.get(key).deepCopy());
            }
        }
        merged.add("npm", structured.get("npm"));
        merged.add("options", structured.get("options"));
        if (structured.has("models")) {
            merged.add("models", structured.get("models"));
        } else {
            merged.remove("models");
        }
        rawConfig = merged.deepCopy();
        return merged;
    }

    private JsonObject buildOmoDraftConfig() {
        JsonObject config = new JsonObject();
        if (!omoAgents.isEmpty()) {
            JsonObject agents = new JsonObject();
            for (Map.Entry<String, JsonObject> entry : omoAgents.entrySet()) {
                if (entry.getKey() != null && !entry.getKey().isBlank()) {
                    agents.add(entry.getKey(), entry.getValue().deepCopy());
                }
            }
            if (!agents.keySet().isEmpty()) {
                config.add("agents", agents);
            }
        }
        if (Provider.CATEGORY_OMO.equals(getCategory()) && !omoCategories.isEmpty()) {
            JsonObject categories = new JsonObject();
            for (Map.Entry<String, JsonObject> entry : omoCategories.entrySet()) {
                if (entry.getKey() != null && !entry.getKey().isBlank()) {
                    categories.add(entry.getKey(), entry.getValue().deepCopy());
                }
            }
            if (!categories.keySet().isEmpty()) {
                config.add("categories", categories);
            }
        }
        JsonObject otherFields = parseJsonObject(otherFieldsArea.getText().trim());
        if (otherFields != null && !otherFields.keySet().isEmpty()) {
            config.add("otherFields", otherFields);
        }
        rawConfig = config.deepCopy();
        return config;
    }

    private JPanel buildOmoPanel() {
        JButton importButton = new JButton("从本地导入");
        importButton.addActionListener(e -> importLocalOmoConfig());
        JButton recommendedButton = new JButton("填充推荐模型");
        recommendedButton.addActionListener(e -> fillRecommendedModels());

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        actions.add(importButton);
        actions.add(Box.createHorizontalStrut(8));
        actions.add(recommendedButton);

        JPanel otherFieldsPanel = new JPanel(new BorderLayout(0, 4));
        otherFieldsPanel.add(new JScrollPane(otherFieldsArea), BorderLayout.CENTER);

        return FormBuilder.createFormBuilder()
                .addComponent(actions)
                .addComponent(modelSourceStatusLabel)
                .addLabeledComponent("主 Agent", mainAgentsPanel)
                .addLabeledComponent("子 Agent", subAgentsPanel)
                .addLabeledComponent("自定义 Agent", customAgentsPanel)
                .addLabeledComponent("Category", categoriesPanel)
                .addLabeledComponent("自定义 Category", customCategoriesPanel)
                .addLabeledComponent("Other Fields", otherFieldsPanel)
                .getPanel();
    }

    private void loadOmoConfig(JsonObject config) {
        clearOmoState();

        if (config.has("agents") && config.get("agents").isJsonObject()) {
            JsonObject agents = config.getAsJsonObject("agents");
            for (String key : agents.keySet()) {
                if (agents.get(key).isJsonObject()) {
                    omoAgents.put(key, agents.getAsJsonObject(key).deepCopy());
                }
            }
        }
        if (config.has("categories") && config.get("categories").isJsonObject()) {
            JsonObject categories = config.getAsJsonObject("categories");
            for (String key : categories.keySet()) {
                if (categories.get(key).isJsonObject()) {
                    omoCategories.put(key, categories.getAsJsonObject(key).deepCopy());
                }
            }
        }

        JsonObject otherFields = config.has("otherFields") && config.get("otherFields").isJsonObject()
                ? config.getAsJsonObject("otherFields")
                : new JsonObject();
        otherFieldsArea.setText(otherFields.keySet().isEmpty() ? "" : GSON.toJson(otherFields));

        refreshCustomKeyLists();
        refreshOmoModelSource();
        rebuildOmoPanels();
    }

    private void clearOmoState() {
        omoAgents.clear();
        omoCategories.clear();
        customAgentKeys.clear();
        customCategoryKeys.clear();
        otherFieldsArea.setText("");
    }

    private void refreshCustomKeyLists() {
        customAgentKeys.clear();
        for (String key : omoAgents.keySet()) {
            boolean builtin = OpenCodeOmoMetadata.getBuiltinAgents(getCategory()).stream()
                    .anyMatch(def -> Objects.equals(def.key(), key));
            if (!builtin) {
                customAgentKeys.add(key);
            }
        }

        customCategoryKeys.clear();
        for (String key : omoCategories.keySet()) {
            boolean builtin = OpenCodeOmoMetadata.getBuiltinCategories(getCategory()).stream()
                    .anyMatch(def -> Objects.equals(def.key(), key));
            if (!builtin) {
                customCategoryKeys.add(key);
            }
        }
    }

    private void switchCategory(String category, boolean fireChange) {
        String safeCategory = category == null || category.isBlank() ? Provider.CATEGORY_CUSTOM : category;
        updatingCategory = true;
        try {
            for (int i = 0; i < categoryCombo.getItemCount(); i++) {
                CategoryItem item = categoryCombo.getItemAt(i);
                if (Objects.equals(item.value(), safeCategory)) {
                    categoryCombo.setSelectedIndex(i);
                    break;
                }
            }
        } finally {
            updatingCategory = false;
        }

        String card = Provider.CATEGORY_CUSTOM.equals(safeCategory) ? MODE_CUSTOM : MODE_OMO;
        ((CardLayout) modePanel.getLayout()).show(modePanel, card);
        categoriesPanel.setVisible(Provider.CATEGORY_OMO.equals(safeCategory));
        customCategoriesPanel.setVisible(Provider.CATEGORY_OMO.equals(safeCategory));
        if (!Provider.CATEGORY_CUSTOM.equals(safeCategory)) {
            refreshOmoModelSource();
            rebuildOmoPanels();
        }
        if (fireChange) {
            notifyChanged();
        }
    }

    private void refreshOmoModelSource() {
        modelChoices.clear();
        variantChoices.clear();

        Map<String, Provider> storedProviders = new LinkedHashMap<>();
        for (Provider provider : ProviderService.getInstance().getOpenCodeCustomProviders()) {
            if (provider.getProviderKey() != null && !provider.getProviderKey().isBlank()) {
                storedProviders.put(provider.getProviderKey(), provider);
            }
        }

        boolean usedFallback = false;
        try {
            JsonObject liveProviders = OpenCodeConfigService.getInstance().getLiveProvidersObjectStrict();
            for (String providerKey : liveProviders.keySet()) {
                if (!liveProviders.get(providerKey).isJsonObject()) {
                    continue;
                }
                Provider stored = storedProviders.get(providerKey);
                String providerName = stored != null ? stored.getName() : providerKey;
                collectModelChoices(providerKey, providerName, liveProviders.getAsJsonObject(providerKey));
            }
        } catch (Exception e) {
            usedFallback = true;
            for (Provider provider : storedProviders.values()) {
                collectModelChoices(provider.getProviderKey(), provider.getName(), provider.getSettingsConfig());
            }
        }

        if (usedFallback) {
            modelSourceStatusLabel.setText("模型来源：读取 live opencode.json 失败，已回退到已保存的普通 OpenCode 配置");
        } else {
            modelSourceStatusLabel.setText("模型来源：当前 live opencode.json 中已同步的普通 OpenCode providers");
        }
    }

    private void collectModelChoices(String providerKey, String providerName, JsonObject providerConfig) {
        String npm = providerConfig.has("npm") && !providerConfig.get("npm").isJsonNull()
                ? providerConfig.get("npm").getAsString()
                : DEFAULT_NPM;
        JsonObject models = providerConfig.has("models") && providerConfig.get("models").isJsonObject()
                ? providerConfig.getAsJsonObject("models")
                : new JsonObject();
        for (String modelId : models.keySet()) {
            JsonObject modelConfig = models.get(modelId).isJsonObject()
                    ? models.getAsJsonObject(modelId)
                    : new JsonObject();
            String displayName = modelConfig.has("name") && !modelConfig.get("name").isJsonNull()
                    ? modelConfig.get("name").getAsString()
                    : modelId;
            String value = providerKey + "/" + modelId;
            String label = providerName + " / " + displayName + " (" + modelId + ")";
            modelChoices.putIfAbsent(value, new ModelChoice(value, label, providerKey, modelId, npm, modelConfig.deepCopy()));

            List<String> variants = new ArrayList<>();
            if (modelConfig.has("variants") && modelConfig.get("variants").isJsonObject()) {
                variants.addAll(modelConfig.getAsJsonObject("variants").keySet());
            }
            if (variants.isEmpty()) {
                variants.addAll(OpenCodeOmoMetadata.getVariantFallback(npm, modelId));
            }
            if (!variants.isEmpty()) {
                variantChoices.put(value, variants);
            }
        }
    }

    private void rebuildOmoPanels() {
        rebuildBuiltinPanel(mainAgentsPanel, OpenCodeOmoMetadata.getBuiltinMainAgents(getCategory()), omoAgents, false);
        rebuildBuiltinPanel(subAgentsPanel, OpenCodeOmoMetadata.getBuiltinSubAgents(getCategory()), omoAgents, false);
        rebuildCustomPanel(customAgentsPanel, customAgentKeys, omoAgents, true, "添加 Agent");

        if (Provider.CATEGORY_OMO.equals(getCategory())) {
            rebuildCategoriesPanel();
            rebuildCustomPanel(customCategoriesPanel, customCategoryKeys, omoCategories, false, "添加 Category");
        } else {
            categoriesPanel.removeAll();
            customCategoriesPanel.removeAll();
        }

        mainAgentsPanel.revalidate();
        subAgentsPanel.revalidate();
        categoriesPanel.revalidate();
        customAgentsPanel.revalidate();
        customCategoriesPanel.revalidate();
        rootPanel.revalidate();
        rootPanel.repaint();
    }

    private void rebuildBuiltinPanel(JPanel panel, List<OpenCodeOmoMetadata.OmoAgentDefinition> definitions,
                                     Map<String, JsonObject> store, boolean removable) {
        panel.removeAll();
        for (OpenCodeOmoMetadata.OmoAgentDefinition definition : definitions) {
            panel.add(createEntryRow(definition.key(), definition.displayName(), definition.recommendedModel(), store, removable, false));
        }
    }

    private void rebuildCategoriesPanel() {
        categoriesPanel.removeAll();
        for (OpenCodeOmoMetadata.OmoCategoryDefinition definition : OpenCodeOmoMetadata.getBuiltinCategories(getCategory())) {
            categoriesPanel.add(createEntryRow(definition.key(), definition.displayName(), definition.recommendedModel(),
                    omoCategories, false, true));
        }
    }

    private void rebuildCustomPanel(JPanel panel, List<String> customKeys, Map<String, JsonObject> store,
                                    boolean agentScope, String addText) {
        panel.removeAll();
        JButton addButton = new JButton(addText);
        addButton.addActionListener(e -> {
            customKeys.add("");
            panel.add(createCustomEntryRow(customKeys, customKeys.size() - 1, store, agentScope));
            panel.revalidate();
            panel.repaint();
            notifyChanged();
        });
        panel.add(addButton);
        for (int index = 0; index < customKeys.size(); index++) {
            panel.add(createCustomEntryRow(customKeys, index, store, agentScope));
        }
    }

    private JPanel createEntryRow(String key, String label, String recommendedModel,
                                  Map<String, JsonObject> store, boolean removable, boolean categoryScope) {
        JPanel row = new JPanel(new BorderLayout(4, 0));
        row.setBorder(JBUI.Borders.empty(2, 0));
        JPanel center = new JPanel(new GridBagLayout());

        String currentModel = getEntryModel(store, key);
        String currentVariant = getEntryVariant(store, key);
        JLabel nameLabel = new JLabel(label);
        nameLabel.setPreferredSize(new Dimension(JBUI.scale(140), nameLabel.getPreferredSize().height));
        JComboBox<ModelChoice> modelCombo = createModelCombo(currentModel);
        JComboBox<String> variantCombo = createVariantCombo(currentModel, currentVariant);
        JButton advancedButton = createAdvancedButton(hasAdvanced(store, key));
        JPanel variantHolder = createVariantHolder(variantCombo, shouldShowVariantCombo(currentModel, currentVariant));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridy = 0;
        gbc.insets = JBUI.insets(0, 0, 0, 4);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        gbc.gridx = 0;
        gbc.weightx = 0;
        center.add(nameLabel, gbc);

        gbc.gridx = 1;
        gbc.weightx = 1.0;
        center.add(modelCombo, gbc);

        gbc.gridx = 2;
        gbc.weightx = 0;
        center.add(variantHolder, gbc);

        gbc.gridx = 3;
        center.add(advancedButton, gbc);
        row.add(center, BorderLayout.CENTER);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JButton recommendButton = new JButton("推荐");
        recommendButton.setPreferredSize(new Dimension(JBUI.scale(68), JBUI.scale(28)));
        recommendButton.addActionListener(e -> {
            String matched = OpenCodeOmoMetadata.resolveRecommendedModelValue(recommendedModel, new ArrayList<>(modelChoices.keySet()));
            if (matched != null) {
                updateEntryModel(store, key, matched);
                rebuildOmoPanels();
                notifyChanged();
            }
        });
        right.add(recommendButton);

        if (removable) {
            JButton removeButton = new JButton("移除");
            removeButton.addActionListener(e -> {
                store.remove(key);
                if (categoryScope) {
                    customCategoryKeys.remove(key);
                } else {
                    customAgentKeys.remove(key);
                }
                rebuildOmoPanels();
                notifyChanged();
            });
            right.add(removeButton);
        }
        row.add(right, BorderLayout.EAST);

        modelCombo.addActionListener(e -> {
            ModelChoice choice = (ModelChoice) modelCombo.getSelectedItem();
            String nextModel = choice != null ? choice.value() : "";
            updateEntryModel(store, key, nextModel);
            String nextVariant = getEntryVariant(store, key);
            variantCombo.setModel(createVariantComboModel(nextModel, nextVariant));
            variantHolder.removeAll();
            variantHolder.add(shouldShowVariantCombo(nextModel, nextVariant)
                    ? variantCombo
                    : Box.createHorizontalStrut(JBUI.scale(96)), BorderLayout.CENTER);
            variantHolder.revalidate();
            variantHolder.repaint();
            notifyChanged();
        });
        variantCombo.addActionListener(e -> updateEntryVariant(store, key, Objects.toString(variantCombo.getSelectedItem(), "")));
        advancedButton.addActionListener(e -> openAdvancedJsonEditor(key, label, store));
        return row;
    }

    private JPanel createCustomEntryRow(List<String> customKeys, int index, Map<String, JsonObject> store, boolean agentScope) {
        String key = customKeys.get(index);
        JPanel row = new JPanel(new BorderLayout(4, 0));
        JPanel center = new JPanel(new GridBagLayout());
        JTextField keyField = new JTextField(key, 12);
        String currentModel = getEntryModel(store, key);
        String currentVariant = getEntryVariant(store, key);
        JComboBox<ModelChoice> modelCombo = createModelCombo(currentModel);
        JComboBox<String> variantCombo = createVariantCombo(currentModel, currentVariant);
        JButton advancedButton = createAdvancedButton(hasAdvanced(store, key));
        JButton removeButton = new JButton("移除");
        JPanel variantHolder = createVariantHolder(variantCombo, shouldShowVariantCombo(currentModel, currentVariant));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridy = 0;
        gbc.insets = JBUI.insets(0, 0, 0, 4);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        gbc.gridx = 0;
        gbc.weightx = 0;
        keyField.setPreferredSize(new Dimension(JBUI.scale(150), keyField.getPreferredSize().height));
        center.add(keyField, gbc);

        gbc.gridx = 1;
        gbc.weightx = 1.0;
        center.add(modelCombo, gbc);

        gbc.gridx = 2;
        gbc.weightx = 0;
        center.add(variantHolder, gbc);

        gbc.gridx = 3;
        center.add(advancedButton, gbc);

        gbc.gridx = 4;
        removeButton.setPreferredSize(new Dimension(JBUI.scale(68), JBUI.scale(28)));
        center.add(removeButton, gbc);
        row.add(center, BorderLayout.CENTER);

        final String[] currentKey = { key };
        addDocumentListener(keyField, () -> {
            String nextKey = keyField.getText().trim();
            customKeys.set(index, nextKey);
            if (!Objects.equals(currentKey[0], nextKey)) {
                JsonObject existing = currentKey[0] != null && !currentKey[0].isBlank() ? store.remove(currentKey[0]) : null;
                if (existing != null && !nextKey.isBlank()) {
                    store.put(nextKey, existing);
                }
                currentKey[0] = nextKey;
            }
            notifyChanged();
        });

        modelCombo.addActionListener(e -> {
            ModelChoice choice = (ModelChoice) modelCombo.getSelectedItem();
            String nextModel = choice != null ? choice.value() : "";
            updateEntryModel(store, currentKey[0], nextModel);
            String nextVariant = getEntryVariant(store, currentKey[0]);
            variantCombo.setModel(createVariantComboModel(nextModel, nextVariant));
            variantHolder.removeAll();
            variantHolder.add(shouldShowVariantCombo(nextModel, nextVariant)
                    ? variantCombo
                    : Box.createHorizontalStrut(JBUI.scale(96)), BorderLayout.CENTER);
            variantHolder.revalidate();
            variantHolder.repaint();
            notifyChanged();
        });
        variantCombo.addActionListener(e -> updateEntryVariant(store, currentKey[0], Objects.toString(variantCombo.getSelectedItem(), "")));
        advancedButton.addActionListener(e -> openAdvancedJsonEditor(currentKey[0], currentKey[0], store));
        removeButton.addActionListener(e -> {
            store.remove(currentKey[0]);
            customKeys.remove(index);
            rebuildOmoPanels();
            notifyChanged();
        });
        return row;
    }

    private JComboBox<ModelChoice> createModelCombo(String currentValue) {
        DefaultComboBoxModel<ModelChoice> model = new DefaultComboBoxModel<>();
        model.addElement(new ModelChoice("", "(未设置)", "", "", DEFAULT_NPM, new JsonObject()));
        modelChoices.values().stream()
                .sorted(Comparator.comparing(ModelChoice::label))
                .forEach(model::addElement);
        if (currentValue != null && !currentValue.isBlank() && !modelChoices.containsKey(currentValue)) {
            model.addElement(new ModelChoice(currentValue, currentValue + " (当前值，未同步)", "", "", DEFAULT_NPM, new JsonObject()));
        }

        JComboBox<ModelChoice> combo = new JComboBox<>(model);
        combo.setPreferredSize(new Dimension(JBUI.scale(320), JBUI.scale(28)));
        combo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public java.awt.Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                                   boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof ModelChoice choice) {
                    setText(choice.label());
                    setToolTipText(choice.label());
                }
                return this;
            }
        });
        for (int i = 0; i < model.getSize(); i++) {
            ModelChoice choice = model.getElementAt(i);
            if (Objects.equals(choice.value(), currentValue)) {
                combo.setSelectedIndex(i);
                combo.setToolTipText(choice.label());
                break;
            }
        }
        combo.addActionListener(e -> {
            Object item = combo.getSelectedItem();
            combo.setToolTipText(item instanceof ModelChoice choice ? choice.label() : null);
        });
        return combo;
    }

    private JComboBox<String> createVariantCombo(String modelValue, String currentVariant) {
        JComboBox<String> combo = new JComboBox<>(createVariantComboModel(modelValue, currentVariant));
        combo.setPreferredSize(new Dimension(JBUI.scale(110), JBUI.scale(28)));
        return combo;
    }

    private DefaultComboBoxModel<String> createVariantComboModel(String modelValue, String currentVariant) {
        DefaultComboBoxModel<String> model = new DefaultComboBoxModel<>();
        model.addElement("");
        List<String> variants = variantChoices.getOrDefault(modelValue, List.of());
        for (String variant : variants) {
            model.addElement(variant);
        }
        if (currentVariant != null && !currentVariant.isBlank() && variants.stream().noneMatch(v -> Objects.equals(v, currentVariant))) {
            model.addElement(currentVariant);
        }
        model.setSelectedItem(currentVariant != null ? currentVariant : "");
        return model;
    }

    private boolean shouldShowVariantCombo(String modelValue, String currentVariant) {
        if (modelValue == null || modelValue.isBlank()) {
            return false;
        }
        List<String> variants = variantChoices.getOrDefault(modelValue, List.of());
        return !variants.isEmpty() || (currentVariant != null && !currentVariant.isBlank());
    }

    private JPanel createVariantHolder(JComboBox<String> variantCombo, boolean visible) {
        JPanel holder = new JPanel(new BorderLayout());
        holder.setPreferredSize(new Dimension(JBUI.scale(110), JBUI.scale(28)));
        holder.add(visible ? variantCombo : Box.createHorizontalStrut(JBUI.scale(96)), BorderLayout.CENTER);
        return holder;
    }

    private JButton createAdvancedButton(boolean hasAdvanced) {
        JButton button = new JButton(hasAdvanced ? "高级 *" : "高级");
        button.setPreferredSize(new Dimension(JBUI.scale(84), JBUI.scale(28)));
        return button;
    }

    private void updateEntryModel(Map<String, JsonObject> store, String key, String modelValue) {
        if (key == null || key.isBlank()) {
            return;
        }
        JsonObject entry = store.getOrDefault(key, new JsonObject()).deepCopy();
        if (modelValue == null || modelValue.isBlank()) {
            entry.remove("model");
            entry.remove("variant");
        } else {
            entry.addProperty("model", modelValue);
            String currentVariant = entry.has("variant") && !entry.get("variant").isJsonNull()
                    ? entry.get("variant").getAsString()
                    : "";
            if (!currentVariant.isBlank() && !variantChoices.getOrDefault(modelValue, List.of()).contains(currentVariant)) {
                entry.remove("variant");
            }
        }
        storeEntry(store, key, entry);
    }

    private void updateEntryVariant(Map<String, JsonObject> store, String key, String variant) {
        if (key == null || key.isBlank()) {
            return;
        }
        JsonObject entry = store.getOrDefault(key, new JsonObject()).deepCopy();
        if (variant == null || variant.isBlank()) {
            entry.remove("variant");
        } else {
            entry.addProperty("variant", variant);
        }
        storeEntry(store, key, entry);
        notifyChanged();
    }

    private void storeEntry(Map<String, JsonObject> store, String key, JsonObject entry) {
        if (key == null || key.isBlank()) {
            return;
        }
        if (entry == null || entry.keySet().isEmpty()) {
            store.remove(key);
        } else {
            store.put(key, entry);
        }
    }

    private void openAdvancedJsonEditor(String key, String title, Map<String, JsonObject> store) {
        if (key == null || key.isBlank()) {
            return;
        }
        JsonObject existing = store.getOrDefault(key, new JsonObject());
        JsonObject advanced = existing.deepCopy();
        advanced.remove("model");
        advanced.remove("variant");
        String initial = advanced.keySet().isEmpty() ? "" : GSON.toJson(advanced);
        JsonObject result = JsonObjectEditorDialog.edit(rootPanel, title + " 高级 JSON", initial);
        if (result == null && initial != null) {
            return;
        }

        JsonObject next = existing.deepCopy();
        String currentModel = next.has("model") && !next.get("model").isJsonNull() ? next.get("model").getAsString() : null;
        String currentVariant = next.has("variant") && !next.get("variant").isJsonNull() ? next.get("variant").getAsString() : null;
        next = result != null ? result.deepCopy() : new JsonObject();
        if (currentModel != null && !currentModel.isBlank()) {
            next.addProperty("model", currentModel);
        }
        if (currentVariant != null && !currentVariant.isBlank()) {
            next.addProperty("variant", currentVariant);
        }
        storeEntry(store, key, next);
        rebuildOmoPanels();
        notifyChanged();
    }

    private void importLocalOmoConfig() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("JSON / JSONC", "json", "jsonc"));
        Path defaultPath = OpenCodeConfigService.getInstance().resolveOmoLocalFilePath(getCategory());
        if (defaultPath != null) {
            chooser.setSelectedFile(defaultPath.toFile());
        }
        if (chooser.showOpenDialog(rootPanel) != JFileChooser.APPROVE_OPTION) {
            return;
        }

        try {
            OpenCodeConfigService.OmoLocalFileData localFileData = OpenCodeConfigService.getInstance()
                    .readOmoLocalFile(chooser.getSelectedFile().toPath(), getCategory());
            JsonObject draft = OpenCodeConfigService.getInstance().toOmoDraftSettings(localFileData, getCategory());
            loadOmoConfig(draft);
            notifyChanged();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(rootPanel, "导入失败: " + ex.getMessage(), "OpenCode", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void fillRecommendedModels() {
        List<String> available = new ArrayList<>(modelChoices.keySet());
        int filled = 0;
        int missing = 0;

        for (OpenCodeOmoMetadata.OmoAgentDefinition definition : OpenCodeOmoMetadata.getBuiltinAgents(getCategory())) {
            JsonObject entry = omoAgents.getOrDefault(definition.key(), new JsonObject());
            if (entry.has("model") && !entry.get("model").getAsString().isBlank()) {
                continue;
            }
            String matched = OpenCodeOmoMetadata.resolveRecommendedModelValue(definition.recommendedModel(), available);
            if (matched == null) {
                missing++;
                continue;
            }
            updateEntryModel(omoAgents, definition.key(), matched);
            filled++;
        }

        if (Provider.CATEGORY_OMO.equals(getCategory())) {
            for (OpenCodeOmoMetadata.OmoCategoryDefinition definition : OpenCodeOmoMetadata.getBuiltinCategories(getCategory())) {
                JsonObject entry = omoCategories.getOrDefault(definition.key(), new JsonObject());
                if (entry.has("model") && !entry.get("model").getAsString().isBlank()) {
                    continue;
                }
                String matched = OpenCodeOmoMetadata.resolveRecommendedModelValue(definition.recommendedModel(), available);
                if (matched == null) {
                    missing++;
                    continue;
                }
                updateEntryModel(omoCategories, definition.key(), matched);
                filled++;
            }
        }

        rebuildOmoPanels();
        notifyChanged();
        if (filled > 0 || missing > 0) {
            JOptionPane.showMessageDialog(rootPanel,
                    "已填充推荐模型 " + filled + " 项" + (missing > 0 ? "，未匹配到 " + missing + " 项" : ""),
                    "OpenCode", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    private String getEntryModel(Map<String, JsonObject> store, String key) {
        JsonObject entry = store.get(key);
        return entry != null && entry.has("model") && !entry.get("model").isJsonNull()
                ? entry.get("model").getAsString()
                : "";
    }

    private String getEntryVariant(Map<String, JsonObject> store, String key) {
        JsonObject entry = store.get(key);
        return entry != null && entry.has("variant") && !entry.get("variant").isJsonNull()
                ? entry.get("variant").getAsString()
                : "";
    }

    private boolean hasAdvanced(Map<String, JsonObject> store, String key) {
        JsonObject entry = store.get(key);
        if (entry == null) {
            return false;
        }
        JsonObject copy = entry.deepCopy();
        copy.remove("model");
        copy.remove("variant");
        return !copy.keySet().isEmpty();
    }

    private JsonObject parseProviderPreview(String previewText) {
        if (previewText == null) {
            return null;
        }
        String cleaned = previewText.replaceFirst("(?s)^\\s*//.*?\\n", "").trim();
        if (cleaned.isBlank()) {
            return new JsonObject();
        }
        JsonElement parsed = JsonParser.parseString(cleaned);
        if (!parsed.isJsonObject()) {
            return null;
        }
        JsonObject root = parsed.getAsJsonObject();
        if (root.has("provider") && root.get("provider").isJsonObject()) {
            JsonObject providerRoot = root.getAsJsonObject("provider");
            if (providerRoot.has("npm")) {
                return providerRoot;
            }
            for (String key : providerRoot.keySet()) {
                if (providerRoot.get(key).isJsonObject()) {
                    return providerRoot.getAsJsonObject(key);
                }
            }
        }
        return root;
    }

    private JsonObject parseJsonObject(String text) {
        if (text == null || text.isBlank()) {
            return new JsonObject();
        }
        try {
            JsonElement parsed = JsonParser.parseString(text);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private JsonElement parseJsonValue(String text) {
        if (text == null || text.isBlank()) {
            return JsonParser.parseString("\"\"");
        }
        try {
            return JsonParser.parseString(text);
        } catch (Exception ignored) {
            return JsonParser.parseString(GSON.toJson(text));
        }
    }

    private String stringifyValue(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return "";
        }
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            return value.getAsString();
        }
        return GSON.toJson(value);
    }

    private void addDocumentListener(JTextComponent textComponent) {
        addDocumentListener(textComponent, this::notifyChanged);
    }

    private void addDocumentListener(JTextComponent textComponent, Runnable runnable) {
        textComponent.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                runnable.run();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                runnable.run();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                runnable.run();
            }
        });
    }

    private void notifyChanged() {
        for (Runnable listener : changeListeners) {
            listener.run();
        }
    }

    private record CategoryItem(String value, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    private record ExtraOptionRow(JTextField keyField, JTextField valueField, JPanel panel) {
    }

    private record ModelRow(JTextField modelIdField, JTextField displayNameField, JTextField optionsField,
                            JPanel panel) {
    }

    private record ModelChoice(String value, String label, String providerKey, String modelId, String npmPackage,
                               JsonObject modelConfig) {
    }

    private static final class JsonObjectEditorDialog extends DialogWrapper {
        private final JTextArea textArea = new JTextArea(18, 60);

        private JsonObjectEditorDialog(java.awt.Component parent, String title, String initialText) {
            super(parent instanceof Window ? (Window) parent : null, true);
            setTitle(title);
            textArea.setText(initialText != null ? initialText : "");
            init();
        }

        static JsonObject edit(java.awt.Component parent, String title, String initialText) {
            JsonObjectEditorDialog dialog = new JsonObjectEditorDialog(parent, title, initialText);
            if (!dialog.showAndGet()) {
                return null;
            }
            String text = dialog.textArea.getText().trim();
            if (text.isBlank()) {
                return new JsonObject();
            }
            JsonElement parsed = JsonParser.parseString(text);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        }

        @Override
        protected @Nullable JComponent createCenterPanel() {
            return new JScrollPane(textArea);
        }

        @Override
        protected @Nullable ValidationInfo doValidate() {
            String text = textArea.getText().trim();
            if (text.isBlank()) {
                return null;
            }
            try {
                JsonElement parsed = JsonParser.parseString(text);
                if (!parsed.isJsonObject()) {
                    return new ValidationInfo("必须输入 JSON 对象", textArea);
                }
            } catch (Exception e) {
                return new ValidationInfo("JSON 解析失败: " + e.getMessage(), textArea);
            }
            return null;
        }
    }
}
