package com.github.mostbean.codingswitch.ui.settings;

import com.github.mostbean.codingswitch.model.AiModelFormat;
import com.github.mostbean.codingswitch.model.AiModelProfile;
import com.github.mostbean.codingswitch.model.AiCompletionLengthLevel;
import com.github.mostbean.codingswitch.service.AiFeatureSettings;
import com.github.mostbean.codingswitch.service.AiModelConnectionTestService;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.actionSystem.KeyboardShortcut;
import com.intellij.openapi.actionSystem.Shortcut;
import com.intellij.openapi.keymap.Keymap;
import com.intellij.openapi.keymap.KeymapManager;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.options.SearchableConfigurable;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.event.InputEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.Scrollable;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.SpinnerNumberModel;
import javax.swing.UIManager;
import javax.swing.table.DefaultTableModel;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * IDE 系统设置页：仅管理插件内 AI 功能，不复用侧边栏设置页。
 */
public class AiFeaturesConfigurable implements SearchableConfigurable {

    private JCheckBox codeCompletionEnabled;
    private JCheckBox gitCommitMessageEnabled;
    private JCheckBox autoCompletionEnabled;
    private JComboBox<AiCompletionLengthLevel> autoCompletionLengthLevel;
    private JComboBox<AiCompletionLengthLevel> manualCompletionLengthLevel;
    private JTextField manualShortcutField;
    private String lastManualShortcut = AiFeatureSettings.DEFAULT_MANUAL_SHORTCUT;
    private boolean capturingShortcut = false;
    private JComboBox<AiModelProfile> activeProfileCombo;
    private DefaultComboBoxModel<AiModelProfile> activeProfileModel;
    private DefaultTableModel profileTableModel;
    private JTable profileTable;
    private JPanel rootPanel;

    private final List<AiModelProfile> profiles = new ArrayList<>();
    private final Map<String, String> editedApiKeys = new HashMap<>();
    private final Set<String> removedProfileIds = new HashSet<>();

    @Override
    public @NotNull String getId() {
        return "com.github.mostbean.codingswitch.aiFeatures";
    }

    @Override
    public @Nls String getDisplayName() {
        return "Coding Switch";
    }

    @Override
    public @Nullable JComponent createComponent() {
        rootPanel = new JPanel(new BorderLayout());
        rootPanel.setBorder(JBUI.Borders.empty(12));
        JBScrollPane scrollPane = new JBScrollPane(buildContent());
        scrollPane.setBorder(JBUI.Borders.empty());
        rootPanel.add(scrollPane, BorderLayout.CENTER);
        reset();
        return rootPanel;
    }

    private JPanel buildContent() {
        JPanel content = new VerticalScrollablePanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.add(buildFeatureSection());
        content.add(Box.createVerticalStrut(12));
        content.add(buildProfileSection());
        content.add(Box.createVerticalStrut(12));
        content.add(buildCompletionSection());
        content.add(Box.createVerticalGlue());
        return content;
    }

    private JPanel buildFeatureSection() {
        JPanel section = createSection("功能开关");
        codeCompletionEnabled = new JCheckBox("启用代码补全功能");
        gitCommitMessageEnabled = new JCheckBox("启用 Git 提交信息生成功能");
        section.add(checkBoxRow(codeCompletionEnabled));
        section.add(checkBoxRow(gitCommitMessageEnabled));
        return section;
    }

    private JPanel buildCompletionSection() {
        JPanel section = createSection("代码补全");

        autoCompletionEnabled = new JCheckBox("启用自动触发补全");
        section.add(checkBoxRow(autoCompletionEnabled));

        JPanel activeRow = rowPanel();
        activeRow.add(new JBLabel("补全模型:"));
        activeProfileModel = new DefaultComboBoxModel<>();
        activeProfileCombo = new JComboBox<>(activeProfileModel);
        activeProfileCombo.setRenderer((JList<? extends AiModelProfile> list, AiModelProfile value, int index,
            boolean isSelected, boolean cellHasFocus) -> {
            JLabel label = new JLabel(value == null ? "未配置模型" : activeProfileDisplayName(value));
            label.setOpaque(true);
            label.setBackground(isSelected ? list.getSelectionBackground() : list.getBackground());
            label.setForeground(isSelected ? list.getSelectionForeground() : list.getForeground());
            label.setBorder(JBUI.Borders.empty(2, 6));
            return label;
        });
        activeProfileCombo.setPreferredSize(new Dimension(JBUI.scale(320), activeProfileCombo.getPreferredSize().height));
        activeRow.add(activeProfileCombo);
        section.add(activeRow);

        JPanel lengthRow = rowPanel();
        lengthRow.add(new JBLabel("自动补全长度:"));
        autoCompletionLengthLevel = new JComboBox<>(AiCompletionLengthLevel.values());
        lengthRow.add(autoCompletionLengthLevel);
        lengthRow.add(new JBLabel("手动补全长度:"));
        manualCompletionLengthLevel = new JComboBox<>(AiCompletionLengthLevel.values());
        lengthRow.add(manualCompletionLengthLevel);
        section.add(lengthRow);

        JPanel shortcutRow = rowPanel();
        shortcutRow.add(new JBLabel("手动触发快捷键:"));
        manualShortcutField = new JBTextField(displayShortcutText(AiFeatureSettings.DEFAULT_MANUAL_SHORTCUT), 18);
        manualShortcutField.setEditable(false);
        shortcutRow.add(manualShortcutField);
        JButton editShortcutButton = new JButton("修改");
        editShortcutButton.addActionListener(e -> startShortcutCapture());
        shortcutRow.add(editShortcutButton);
        JBLabel hint = new JBLabel("仅支持一个修饰键 + 一个普通键");
        hint.setForeground(JBColor.GRAY);
        shortcutRow.add(hint);
        section.add(shortcutRow);

        return section;
    }

    private String activeProfileDisplayName(AiModelProfile profile) {
        String name = profile.getDisplayName();
        String model = profile.getModel();
        return model.isBlank() ? name : name + " / " + model;
    }

    private JPanel buildProfileSection() {
        JPanel section = createSection("模型配置");

        JPanel row = rowPanel();
        JButton configureButton = new JButton("模型配置...", AllIcons.Actions.Edit);
        configureButton.addActionListener(e -> showProfileManagerDialog());
        row.add(configureButton);
        section.add(row);
        return section;
    }

    private void startShortcutCapture() {
        lastManualShortcut = normalizeShortcutText(manualShortcutField.getText()).isBlank()
            ? AiFeatureSettings.DEFAULT_MANUAL_SHORTCUT
            : normalizeShortcutText(manualShortcutField.getText());
        capturingShortcut = true;
        manualShortcutField.setText("请按快捷键...");
        manualShortcutField.setForeground(JBColor.GRAY);
        manualShortcutField.requestFocusInWindow();
        for (java.awt.event.KeyListener listener : manualShortcutField.getKeyListeners()) {
            manualShortcutField.removeKeyListener(listener);
        }
        manualShortcutField.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                e.consume();
                String shortcut = toTwoKeyShortcut(e);
                if (shortcut == null) {
                    Toolkit.getDefaultToolkit().beep();
                    return;
                }
                lastManualShortcut = shortcut;
                showShortcutValue(shortcut);
                manualShortcutField.removeKeyListener(this);
            }
        });
    }

    private String toTwoKeyShortcut(KeyEvent e) {
        int keyCode = e.getKeyCode();
        if (keyCode == KeyEvent.VK_CONTROL
            || keyCode == KeyEvent.VK_ALT
            || keyCode == KeyEvent.VK_SHIFT
            || keyCode == KeyEvent.VK_META) {
            return null;
        }
        int modifiers = e.getModifiersEx();
        String modifier = singleModifierText(modifiers);
        if (modifier == null) {
            return null;
        }
        String keyText = toKeyStrokeText(keyCode);
        if (keyText == null) {
            return null;
        }
        return modifier + " " + keyText;
    }

    private String toKeyStrokeText(int keyCode) {
        if (keyCode >= KeyEvent.VK_A && keyCode <= KeyEvent.VK_Z) {
            return String.valueOf((char) keyCode);
        }
        if (keyCode >= KeyEvent.VK_0 && keyCode <= KeyEvent.VK_9) {
            return String.valueOf((char) keyCode);
        }
        if (keyCode >= KeyEvent.VK_F1 && keyCode <= KeyEvent.VK_F24) {
            return "F" + (keyCode - KeyEvent.VK_F1 + 1);
        }
        return switch (keyCode) {
            case KeyEvent.VK_SPACE -> "SPACE";
            case KeyEvent.VK_ENTER -> "ENTER";
            case KeyEvent.VK_TAB -> "TAB";
            case KeyEvent.VK_ESCAPE -> "ESCAPE";
            case KeyEvent.VK_BACK_SPACE -> "BACK_SPACE";
            case KeyEvent.VK_DELETE -> "DELETE";
            case KeyEvent.VK_INSERT -> "INSERT";
            case KeyEvent.VK_HOME -> "HOME";
            case KeyEvent.VK_END -> "END";
            case KeyEvent.VK_PAGE_UP -> "PAGE_UP";
            case KeyEvent.VK_PAGE_DOWN -> "PAGE_DOWN";
            case KeyEvent.VK_UP -> "UP";
            case KeyEvent.VK_DOWN -> "DOWN";
            case KeyEvent.VK_LEFT -> "LEFT";
            case KeyEvent.VK_RIGHT -> "RIGHT";
            default -> null;
        };
    }

    private String singleModifierText(int modifiers) {
        int count = 0;
        String value = null;
        if ((modifiers & KeyEvent.CTRL_DOWN_MASK) != 0) {
            count++;
            value = "control";
        }
        if ((modifiers & KeyEvent.ALT_DOWN_MASK) != 0) {
            count++;
            value = "alt";
        }
        if ((modifiers & KeyEvent.SHIFT_DOWN_MASK) != 0) {
            count++;
            value = "shift";
        }
        if ((modifiers & KeyEvent.META_DOWN_MASK) != 0) {
            count++;
            value = "meta";
        }
        return count == 1 ? value : null;
    }

    private void showProfileManagerDialog() {
        List<AiModelProfile> profileSnapshot = new ArrayList<>();
        for (AiModelProfile profile : profiles) {
            profileSnapshot.add(profile.copy());
        }
        Map<String, String> editedApiKeysSnapshot = new HashMap<>(editedApiKeys);
        Set<String> removedProfileIdsSnapshot = new HashSet<>(removedProfileIds);
        Object activeSnapshot = activeProfileCombo.getSelectedItem();

        ProfileManagerDialog dialog = new ProfileManagerDialog();
        if (!dialog.showAndGet()) {
            profiles.clear();
            profiles.addAll(profileSnapshot);
            editedApiKeys.clear();
            editedApiKeys.putAll(editedApiKeysSnapshot);
            removedProfileIds.clear();
            removedProfileIds.addAll(removedProfileIdsSnapshot);
            reloadProfiles();
            if (activeSnapshot instanceof AiModelProfile profile) {
                selectProfile(profile.getId());
            }
        }
    }

    private JPanel createSection(String title) {
        JPanel section = new JPanel();
        section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
        section.setBorder(BorderFactory.createTitledBorder(BorderFactory.createEtchedBorder(), title));
        section.setAlignmentX(Component.LEFT_ALIGNMENT);
        section.setMaximumSize(new Dimension(Integer.MAX_VALUE, section.getMaximumSize().height));
        return section;
    }

    private JPanel rowPanel() {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 6));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        return row;
    }

    private JPanel checkBoxRow(JCheckBox checkBox) {
        JPanel row = rowPanel();
        row.add(checkBox);
        return row;
    }

    private JPanel wrappedHint(String text) {
        JPanel row = new JPanel(new BorderLayout());
        row.setBorder(JBUI.Borders.empty(0, 10, 8, 10));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);

        JTextArea hint = new JTextArea(text);
        hint.setEditable(false);
        hint.setLineWrap(true);
        hint.setWrapStyleWord(true);
        hint.setOpaque(false);
        hint.setFocusable(false);
        hint.setForeground(JBColor.GRAY);
        hint.setBorder(JBUI.Borders.empty());
        hint.setColumns(20);
        row.add(hint, BorderLayout.CENTER);
        return row;
    }

    private void addProfile() {
        ProfileDialog dialog = new ProfileDialog(null, "");
        if (!dialog.showAndGet()) {
            return;
        }
        AiModelProfile profile = dialog.getProfile();
        profiles.add(profile);
        if (dialog.getApiKey() != null && !dialog.getApiKey().isBlank()) {
            editedApiKeys.put(profile.getId(), dialog.getApiKey());
        }
        if (activeProfileModel.getSize() == 0) {
            activeProfileCombo.setSelectedItem(profile);
        }
        reloadProfiles();
    }

    private void editSelectedProfile() {
        int row = profileTable.getSelectedRow();
        if (row < 0 || row >= profiles.size()) {
            return;
        }
        AiModelProfile original = profiles.get(row);
        String existingKey = editedApiKeys.getOrDefault(
            original.getId(),
            AiFeatureSettings.getInstance().getApiKey(original.getId())
        );
        ProfileDialog dialog = new ProfileDialog(original, existingKey);
        if (!dialog.showAndGet()) {
            return;
        }
        AiModelProfile updated = dialog.getProfile();
        profiles.set(row, updated);
        String apiKey = dialog.getApiKey();
        if (!Objects.equals(apiKey, existingKey)) {
            editedApiKeys.put(updated.getId(), apiKey);
        }
        reloadProfiles();
        selectProfile(updated.getId());
    }

    private void removeSelectedProfile() {
        int row = profileTable.getSelectedRow();
        if (row < 0 || row >= profiles.size()) {
            return;
        }
        AiModelProfile removed = profiles.remove(row);
        removedProfileIds.add(removed.getId());
        editedApiKeys.remove(removed.getId());
        reloadProfiles();
    }

    private void setSelectedProfileActive() {
        int row = profileTable.getSelectedRow();
        if (row < 0 || row >= profiles.size()) {
            return;
        }
        activeProfileCombo.setSelectedItem(profiles.get(row));
    }

    private void reloadProfiles() {
        if (profileTableModel != null) {
            profileTableModel.setRowCount(0);
        }
        if (activeProfileModel != null) {
            activeProfileModel.removeAllElements();
        }
        for (AiModelProfile profile : profiles) {
            if (profileTableModel != null) {
                profileTableModel.addRow(new Object[] {
                    profile.getDisplayName(),
                    profile.getFormat().getDisplayName(),
                    profile.getBaseUrl(),
                    profile.getModel()
                });
            }
            if (activeProfileModel != null) {
                activeProfileModel.addElement(profile);
            }
        }
        if (activeProfileModel != null
            && activeProfileModel.getSize() > 0
            && activeProfileCombo != null
            && activeProfileCombo.getSelectedItem() == null) {
            activeProfileCombo.setSelectedIndex(0);
        }
    }

    private void selectProfile(String profileId) {
        for (AiModelProfile profile : profiles) {
            if (Objects.equals(profile.getId(), profileId)) {
                activeProfileCombo.setSelectedItem(profile);
                return;
            }
        }
    }

    @Override
    public boolean isModified() {
        AiFeatureSettings.State current = AiFeatureSettings.getInstance().snapshot();
        AiFeatureSettings.State next = collectState();
        return !statesEqual(current, next) || !editedApiKeys.isEmpty() || !removedProfileIds.isEmpty();
    }

    @Override
    public void apply() throws ConfigurationException {
        String shortcut = capturingShortcut ? lastManualShortcut : normalizeShortcutText(manualShortcutField.getText().trim());
        if (shortcut.isBlank()) {
            shortcut = lastManualShortcut;
        }
        showShortcutValue(shortcut);
        if (!isTwoKeyShortcut(shortcut)) {
            throw new ConfigurationException("手动补全快捷键必须是一个修饰键 + 一个普通键，例如 Ctrl + Space");
        }
        AiFeatureSettings.State next = collectState();
        AiFeatureSettings.getInstance().update(next);
        for (String profileId : removedProfileIds) {
            AiFeatureSettings.getInstance().clearApiKey(profileId);
        }
        for (Map.Entry<String, String> entry : editedApiKeys.entrySet()) {
            AiFeatureSettings.getInstance().setApiKey(entry.getKey(), entry.getValue());
        }
        applyShortcut(shortcut);
        removedProfileIds.clear();
        editedApiKeys.clear();
    }

    @Override
    public void reset() {
        AiFeatureSettings.State state = AiFeatureSettings.getInstance().snapshot();
        codeCompletionEnabled.setSelected(state.codeCompletionEnabled);
        gitCommitMessageEnabled.setSelected(state.gitCommitMessageEnabled);
        autoCompletionEnabled.setSelected(state.autoCompletionEnabled);
        autoCompletionLengthLevel.setSelectedItem(parseLengthLevel(
            state.autoCompletionLengthLevel,
            AiCompletionLengthLevel.SINGLE_LINE
        ));
        manualCompletionLengthLevel.setSelectedItem(parseLengthLevel(
            state.manualCompletionLengthLevel,
            AiCompletionLengthLevel.MEDIUM
        ));
        lastManualShortcut = normalizeShortcutText(state.manualCompletionShortcut);
        showShortcutValue(lastManualShortcut);

        profiles.clear();
        for (AiModelProfile profile : state.profiles) {
            profiles.add(profile.copy());
        }
        reloadProfiles();
        selectProfile(state.activeCompletionProfileId);
        removedProfileIds.clear();
        editedApiKeys.clear();
    }

    private AiCompletionLengthLevel parseLengthLevel(String value, AiCompletionLengthLevel fallback) {
        try {
            return AiCompletionLengthLevel.valueOf(value);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    @Override
    public void disposeUIResources() {
        rootPanel = null;
    }

    private AiFeatureSettings.State collectState() {
        AiFeatureSettings.State state = new AiFeatureSettings.State();
        state.codeCompletionEnabled = codeCompletionEnabled != null && codeCompletionEnabled.isSelected();
        state.gitCommitMessageEnabled = gitCommitMessageEnabled != null && gitCommitMessageEnabled.isSelected();
        state.autoCompletionEnabled = autoCompletionEnabled != null && autoCompletionEnabled.isSelected();
        state.autoCompletionLengthLevel = selectedLengthName(
            autoCompletionLengthLevel,
            AiCompletionLengthLevel.SINGLE_LINE
        );
        state.manualCompletionLengthLevel = selectedLengthName(
            manualCompletionLengthLevel,
            AiCompletionLengthLevel.MEDIUM
        );
        state.manualCompletionShortcut = manualShortcutField == null
            ? AiFeatureSettings.DEFAULT_MANUAL_SHORTCUT
            : manualShortcutValue();
        Object selected = activeProfileCombo == null ? null : activeProfileCombo.getSelectedItem();
        state.activeCompletionProfileId = selected instanceof AiModelProfile profile ? profile.getId() : "";
        state.profiles = new ArrayList<>();
        for (AiModelProfile profile : profiles) {
            state.profiles.add(profile.copy());
        }
        return AiFeatureSettings.normalize(state);
    }

    private boolean statesEqual(AiFeatureSettings.State left, AiFeatureSettings.State right) {
        AiFeatureSettings.State a = AiFeatureSettings.normalize(AiFeatureSettings.copyState(left));
        AiFeatureSettings.State b = AiFeatureSettings.normalize(AiFeatureSettings.copyState(right));
        return a.codeCompletionEnabled == b.codeCompletionEnabled
            && a.gitCommitMessageEnabled == b.gitCommitMessageEnabled
            && a.autoCompletionEnabled == b.autoCompletionEnabled
            && Objects.equals(a.autoCompletionLengthLevel, b.autoCompletionLengthLevel)
            && Objects.equals(a.manualCompletionLengthLevel, b.manualCompletionLengthLevel)
            && Objects.equals(a.activeCompletionProfileId, b.activeCompletionProfileId)
            && Objects.equals(a.manualCompletionShortcut, b.manualCompletionShortcut)
            && Objects.equals(a.profiles, b.profiles);
    }

    private String manualShortcutValue() {
        if (capturingShortcut) {
            return lastManualShortcut;
        }
        String value = normalizeShortcutText(manualShortcutField.getText());
        return value.isBlank() ? lastManualShortcut : value;
    }

    private void showShortcutValue(String shortcut) {
        capturingShortcut = false;
        manualShortcutField.setText(displayShortcutText(shortcut));
        manualShortcutField.setForeground(UIManager.getColor("TextField.foreground"));
        if (manualShortcutField instanceof JBTextField field) {
            field.getEmptyText().setText("");
        }
    }

    private String selectedLengthName(
        JComboBox<AiCompletionLengthLevel> comboBox,
        AiCompletionLengthLevel fallback
    ) {
        Object selected = comboBox == null ? null : comboBox.getSelectedItem();
        return selected instanceof AiCompletionLengthLevel level ? level.name() : fallback.name();
    }

    private boolean isTwoKeyShortcut(String shortcutText) {
        KeyStroke keyStroke = KeyStroke.getKeyStroke(normalizeShortcutText(shortcutText));
        if (keyStroke == null || keyStroke.getKeyCode() == KeyEvent.VK_UNDEFINED) {
            return false;
        }
        int keyCode = keyStroke.getKeyCode();
        if (keyCode == KeyEvent.VK_CONTROL
            || keyCode == KeyEvent.VK_ALT
            || keyCode == KeyEvent.VK_SHIFT
            || keyCode == KeyEvent.VK_META) {
            return false;
        }
        int modifiers = keyStroke.getModifiers();
        int count = 0;
        if ((modifiers & InputEvent.CTRL_DOWN_MASK) != 0 || (modifiers & InputEvent.CTRL_MASK) != 0) {
            count++;
        }
        if ((modifiers & InputEvent.ALT_DOWN_MASK) != 0 || (modifiers & InputEvent.ALT_MASK) != 0) {
            count++;
        }
        if ((modifiers & InputEvent.SHIFT_DOWN_MASK) != 0 || (modifiers & InputEvent.SHIFT_MASK) != 0) {
            count++;
        }
        if ((modifiers & InputEvent.META_DOWN_MASK) != 0 || (modifiers & InputEvent.META_MASK) != 0) {
            count++;
        }
        return count == 1;
    }

    private String normalizeShortcutText(String shortcutText) {
        if (shortcutText == null) {
            return "";
        }
        String normalized = shortcutText.trim()
            .replace("+", " ")
            .replace("空格", "SPACE")
            .replace("回车", "ENTER")
            .replace("制表符", "TAB")
            .replace("删除", "DELETE")
            .replace("退格", "BACK_SPACE")
            .replaceAll("\\s+", " ");
        String[] parts = normalized.split(" ");
        if (parts.length != 2) {
            return normalized;
        }
        return normalizeModifierText(parts[0]) + " " + normalizeKeyText(parts[1]);
    }

    private String normalizeModifierText(String value) {
        return switch (value.toLowerCase()) {
            case "ctrl", "control" -> "control";
            case "alt" -> "alt";
            case "shift" -> "shift";
            case "meta", "cmd", "command" -> "meta";
            default -> value.toLowerCase();
        };
    }

    private String normalizeKeyText(String value) {
        String upper = value.toUpperCase();
        return switch (upper) {
            case "SPACE" -> "SPACE";
            case "ENTER" -> "ENTER";
            case "TAB" -> "TAB";
            case "ESC" -> "ESCAPE";
            case "ESCAPE" -> "ESCAPE";
            case "BACKSPACE" -> "BACK_SPACE";
            case "BACK_SPACE" -> "BACK_SPACE";
            case "DELETE" -> "DELETE";
            case "INSERT" -> "INSERT";
            case "HOME" -> "HOME";
            case "END" -> "END";
            case "PAGEUP", "PAGE_UP" -> "PAGE_UP";
            case "PAGEDOWN", "PAGE_DOWN" -> "PAGE_DOWN";
            case "UP" -> "UP";
            case "DOWN" -> "DOWN";
            case "LEFT" -> "LEFT";
            case "RIGHT" -> "RIGHT";
            default -> upper;
        };
    }

    private String displayShortcutText(String shortcutText) {
        String normalized = normalizeShortcutText(shortcutText);
        String[] parts = normalized.split(" ");
        if (parts.length != 2) {
            return normalized;
        }
        return displayModifierText(parts[0]) + " + " + displayKeyText(parts[1]);
    }

    private String displayModifierText(String value) {
        return switch (value) {
            case "control" -> "Ctrl";
            case "alt" -> "Alt";
            case "shift" -> "Shift";
            case "meta" -> "Meta";
            default -> capitalize(value);
        };
    }

    private String displayKeyText(String value) {
        if (value.length() == 1 || value.matches("F\\d{1,2}")) {
            return value;
        }
        return switch (value) {
            case "SPACE" -> "Space";
            case "ENTER" -> "Enter";
            case "TAB" -> "Tab";
            case "ESCAPE" -> "Escape";
            case "BACK_SPACE" -> "Backspace";
            case "PAGE_UP" -> "Pageup";
            case "PAGE_DOWN" -> "Pagedown";
            default -> capitalize(value.toLowerCase());
        };
    }

    private String capitalize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String lower = value.toLowerCase();
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    private void applyShortcut(String shortcutText) {
        Keymap keymap = KeymapManager.getInstance().getActiveKeymap();
        KeyboardShortcut targetShortcut = new KeyboardShortcut(KeyStroke.getKeyStroke(shortcutText), null);
        for (String actionId : keymap.getActionIds(targetShortcut)) {
            if (!AiFeatureSettings.MANUAL_COMPLETION_ACTION_ID.equals(actionId)) {
                keymap.removeShortcut(actionId, targetShortcut);
            }
        }
        for (Shortcut shortcut : keymap.getShortcuts(AiFeatureSettings.MANUAL_COMPLETION_ACTION_ID)) {
            keymap.removeShortcut(AiFeatureSettings.MANUAL_COMPLETION_ACTION_ID, shortcut);
        }
        keymap.addShortcut(AiFeatureSettings.MANUAL_COMPLETION_ACTION_ID, targetShortcut);
    }

    private final class ProfileManagerDialog extends DialogWrapper {

        private ProfileManagerDialog() {
            super(true);
            setTitle("模型配置");
            init();
        }

        @Override
        protected @Nullable JComponent createCenterPanel() {
            JPanel panel = new JPanel(new BorderLayout(0, 8));
            panel.setBorder(JBUI.Borders.empty(8));

            String[] columns = {"名称", "格式", "Base URL", "模型"};
            profileTableModel = new DefaultTableModel(columns, 0) {
                @Override
                public boolean isCellEditable(int row, int column) {
                    return false;
                }
            };
            profileTable = new JTable(profileTableModel);
            profileTable.setRowHeight(JBUI.scale(24));
            profileTable.getTableHeader().setReorderingAllowed(false);
            profileTable.getColumnModel().getColumn(0).setPreferredWidth(JBUI.scale(130));
            profileTable.getColumnModel().getColumn(1).setPreferredWidth(JBUI.scale(170));
            profileTable.getColumnModel().getColumn(2).setPreferredWidth(JBUI.scale(240));
            profileTable.getColumnModel().getColumn(3).setPreferredWidth(JBUI.scale(180));

            JBScrollPane tablePane = new JBScrollPane(profileTable);
            tablePane.setPreferredSize(new Dimension(JBUI.scale(760), JBUI.scale(220)));
            panel.add(tablePane, BorderLayout.CENTER);

            JPanel buttonRow = rowPanel();
            JButton addButton = new JButton("新增", AllIcons.General.Add);
            addButton.addActionListener(e -> addProfile());
            buttonRow.add(addButton);

            JButton editButton = new JButton("编辑", AllIcons.Actions.Edit);
            editButton.addActionListener(e -> editSelectedProfile());
            buttonRow.add(editButton);

            JButton removeButton = new JButton("删除", AllIcons.General.Remove);
            removeButton.addActionListener(e -> removeSelectedProfile());
            buttonRow.add(removeButton);

            JButton setActiveButton = new JButton("设为补全模型");
            setActiveButton.addActionListener(e -> setSelectedProfileActive());
            buttonRow.add(setActiveButton);
            panel.add(buttonRow, BorderLayout.SOUTH);

            reloadProfiles();
            return panel;
        }
    }

    private static final class VerticalScrollablePanel extends JPanel implements Scrollable {

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return JBUI.scale(16);
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return Math.max(JBUI.scale(16), visibleRect.height - JBUI.scale(16));
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    private static final class ProfileDialog extends DialogWrapper {

        private final AiModelProfile original;
        private final String existingApiKey;
        private JTextField nameField;
        private JComboBox<AiModelFormat> formatCombo;
        private JTextField baseUrlField;
        private JTextField modelField;
        private JPasswordField apiKeyField;
        private JSpinner timeoutSpinner;
        private JTextArea headersArea;
        private JButton testButton;
        private JButton detectModelsButton;
        private JBLabel testStatusLabel;
        private JButton testDetailButton;
        private String latestTestDetail = "";

        private ProfileDialog(AiModelProfile profile, String existingApiKey) {
            super(true);
            this.original = profile == null ? new AiModelProfile() : profile.copy();
            this.existingApiKey = existingApiKey == null ? "" : existingApiKey;
            setTitle(profile == null ? "新增模型配置" : "编辑模型配置");
            init();
        }

        @Override
        protected @Nullable JComponent createCenterPanel() {
            nameField = new JTextField(original.getName(), 30);
            formatCombo = new JComboBox<>(AiModelFormat.values());
            formatCombo.setSelectedItem(original.getFormat());
            baseUrlField = new JTextField(original.getBaseUrl(), 30);
            modelField = new JTextField(original.getModel(), 30);
            apiKeyField = new JPasswordField(existingApiKey, 30);
            timeoutSpinner = new JSpinner(new SpinnerNumberModel(original.getTimeoutSeconds(), 1, 120, 1));
            headersArea = new JTextArea(original.getHeadersJson(), 4, 30);
            headersArea.setLineWrap(true);
            headersArea.setWrapStyleWord(true);

            AiModelFormat[] previousFormat = {original.getFormat()};
            formatCombo.addActionListener(e -> {
                AiModelFormat selected = (AiModelFormat) formatCombo.getSelectedItem();
                if (selected == null) {
                    return;
                }
                String currentBaseUrl = baseUrlField.getText().trim();
                String previousDefaultBaseUrl = previousFormat[0].getDefaultBaseUrl();
                if (currentBaseUrl.isBlank() || currentBaseUrl.equals(previousDefaultBaseUrl)) {
                    baseUrlField.setText(selected.getDefaultBaseUrl());
                }
                previousFormat[0] = selected;
            });

            FormBuilder form = FormBuilder.createFormBuilder()
                .addLabeledComponent("名称:", nameField)
                .addLabeledComponent("协议格式:", formatCombo)
                .addLabeledComponent("Base URL:", baseUrlField)
                .addLabeledComponent("模型:", modelField)
                .addLabeledComponent("API Key:", apiKeyField)
                .addLabeledComponent("超时秒数:", timeoutSpinner)
                .addLabeledComponent("自定义 Headers JSON:", new JBScrollPane(headersArea));

            JPanel buttonRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
            testButton = new JButton("测试配置");
            testButton.addActionListener(e -> runConnectionTest());
            buttonRow.add(testButton);

            detectModelsButton = new JButton("检测模型");
            detectModelsButton.addActionListener(e -> detectModels());
            buttonRow.add(detectModelsButton);

            testStatusLabel = new JBLabel(" ");
            testStatusLabel.setForeground(JBColor.GRAY);
            testStatusLabel.setPreferredSize(new Dimension(JBUI.scale(220), testStatusLabel.getPreferredSize().height));
            buttonRow.add(testStatusLabel);

            testDetailButton = new JButton("详情");
            testDetailButton.setVisible(false);
            testDetailButton.addActionListener(e -> showTestDetail());
            buttonRow.add(testDetailButton);

            JPanel panel = new JPanel(new BorderLayout(0, 10));
            panel.setBorder(JBUI.Borders.empty(8));
            panel.add(form.getPanel(), BorderLayout.CENTER);
            panel.add(buttonRow, BorderLayout.SOUTH);
            panel.setPreferredSize(new Dimension(JBUI.scale(620), JBUI.scale(400)));
            return panel;
        }

        @Override
        protected @Nullable ValidationInfo doValidate() {
            if (modelField.getText().trim().isBlank()) {
                return new ValidationInfo("请填写模型名称", modelField);
            }
            if (baseUrlField.getText().trim().isBlank()) {
                return new ValidationInfo("请填写 Base URL", baseUrlField);
            }
            String headers = headersArea.getText().trim();
            if (!headers.isBlank()) {
                try {
                    if (!JsonParser.parseString(headers).isJsonObject()) {
                        return new ValidationInfo("Headers 必须是 JSON 对象", headersArea);
                    }
                } catch (Exception ex) {
                    return new ValidationInfo("Headers JSON 格式不正确: " + ex.getMessage(), headersArea);
                }
            }
            return null;
        }

        private AiModelProfile getProfile() {
            AiModelProfile profile = original.copy();
            profile.setName(nameField.getText());
            profile.setFormat((AiModelFormat) formatCombo.getSelectedItem());
            profile.setBaseUrl(baseUrlField.getText());
            profile.setModel(modelField.getText());
            profile.setTimeoutSeconds((Integer) timeoutSpinner.getValue());
            profile.setHeadersJson(normalizeHeaders(headersArea.getText()));
            return profile;
        }

        private String getApiKey() {
            return new String(apiKeyField.getPassword()).trim();
        }

        private String normalizeHeaders(String value) {
            String text = value == null ? "" : value.trim();
            if (text.isBlank()) {
                return "";
            }
            JsonObject object = JsonParser.parseString(text).getAsJsonObject();
            return object.toString();
        }

        private void runConnectionTest() {
            AiModelProfile profile;
            try {
                profile = getProfile();
            } catch (Exception ex) {
                setTestStatus("配置格式错误: " + ex.getMessage(), false);
                return;
            }
            String apiKey = getApiKey();
            setTestingButtonsEnabled(false);
            setTestStatus("正在测试配置...", true);

            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                AiModelConnectionTestService.TestResult result =
                    AiModelConnectionTestService.getInstance().test(profile, apiKey);
                SwingUtilities.invokeLater(() -> {
                    setTestingButtonsEnabled(true);
                    setTestStatus(result.message() + " (" + result.durationMs() + " ms)", result.success());
                });
            });
        }

        private void detectModels() {
            AiModelProfile profile;
            try {
                profile = getProfile();
            } catch (Exception ex) {
                setTestStatus("配置格式错误: " + ex.getMessage(), false);
                return;
            }
            String apiKey = getApiKey();
            setTestingButtonsEnabled(false);
            setTestStatus("正在检测模型...", true);

            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                AiModelConnectionTestService.ModelListResult result =
                    AiModelConnectionTestService.getInstance().listModels(profile, apiKey);
                SwingUtilities.invokeLater(() -> {
                    setTestingButtonsEnabled(true);
                    if (!result.success()) {
                        setTestStatus(result.message() + " (" + result.durationMs() + " ms)", false);
                        return;
                    }
                    setTestStatus("检测到 " + result.models().size() + " 个模型 (" + result.durationMs() + " ms)", true);
                    ModelSelectionDialog dialog = new ModelSelectionDialog(result.models());
                    if (dialog.showAndGet()) {
                        modelField.setText(dialog.getSelectedModel());
                    }
                });
            });
        }

        private void setTestingButtonsEnabled(boolean enabled) {
            testButton.setEnabled(enabled);
            detectModelsButton.setEnabled(enabled);
        }

        private void setTestStatus(String message, boolean success) {
            String normalized = message == null ? "" : message.trim();
            latestTestDetail = normalized;
            testStatusLabel.setText(shortStatus(normalized, success));
            testStatusLabel.setToolTipText(normalized.isBlank() ? null : normalized);
            testStatusLabel.setForeground(success ? JBColor.GRAY : JBColor.RED);
            testDetailButton.setVisible(!success && normalized.length() > 40);
        }

        private String shortStatus(String message, boolean success) {
            if (message == null || message.isBlank()) {
                return " ";
            }
            if (success) {
                return truncateStatus(message);
            }
            return "失败，点击详情查看";
        }

        private String truncateStatus(String message) {
            int max = 36;
            return message.length() <= max ? message : message.substring(0, max - 3) + "...";
        }

        private void showTestDetail() {
            if (latestTestDetail == null || latestTestDetail.isBlank()) {
                return;
            }
            Messages.showErrorDialog(latestTestDetail, "模型配置检测失败");
        }
    }

    private static final class ModelSelectionDialog extends DialogWrapper {

        private final List<String> models;
        private JList<String> modelList;

        private ModelSelectionDialog(List<String> models) {
            super(true);
            this.models = models;
            setTitle("检测到的模型");
            init();
        }

        @Override
        protected @Nullable JComponent createCenterPanel() {
            modelList = new JList<>(models.toArray(String[]::new));
            modelList.setVisibleRowCount(14);
            if (!models.isEmpty()) {
                modelList.setSelectedIndex(0);
            }
            JBScrollPane scrollPane = new JBScrollPane(modelList);
            scrollPane.setPreferredSize(new Dimension(JBUI.scale(520), JBUI.scale(300)));
            return scrollPane;
        }

        private String getSelectedModel() {
            String selected = modelList.getSelectedValue();
            return selected == null ? "" : selected;
        }
    }
}
