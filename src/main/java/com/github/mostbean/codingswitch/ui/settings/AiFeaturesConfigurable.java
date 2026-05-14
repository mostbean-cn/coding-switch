package com.github.mostbean.codingswitch.ui.settings;

import com.github.mostbean.codingswitch.model.AiModelFormat;
import com.github.mostbean.codingswitch.model.AiModelProfile;
import com.github.mostbean.codingswitch.model.AiCompletionLengthLevel;
import com.github.mostbean.codingswitch.service.AiFeatureSettings;
import com.github.mostbean.codingswitch.service.AiModelConnectionTestService;
import com.github.mostbean.codingswitch.service.I18n;
import com.github.mostbean.codingswitch.service.PluginDataStorage;
import com.github.mostbean.codingswitch.service.PluginSettings;
import com.github.mostbean.codingswitch.service.PluginStorageModeService;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.actionSystem.KeyboardShortcut;
import com.intellij.openapi.actionSystem.Shortcut;
import com.intellij.openapi.keymap.Keymap;
import com.intellij.openapi.keymap.KeymapManager;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.options.SearchableConfigurable;
import com.intellij.openapi.application.PathManager;
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
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.event.InputEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
import javax.swing.Action;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
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
    private JComboBox<AiFeatureSettings.GitCommitMessageLanguage> gitCommitMessageLanguage;
    private JComboBox<PluginSettings.Language> uiLanguageCombo;
    private JComboBox<PluginSettings.DataStorageMode> storageModeCombo;
    private JCheckBox autoCompletionEnabled;
    private JComboBox<AiCompletionLengthLevel> autoCompletionLengthLevel;
    private JComboBox<AiCompletionLengthLevel> manualCompletionLengthLevel;
    private JTextField manualShortcutField;
    private KeyAdapter shortcutCaptureListener;
    private String lastManualShortcut = AiFeatureSettings.DEFAULT_MANUAL_SHORTCUT;
    private boolean capturingShortcut = false;
    private boolean suppressFeatureAvailabilityUpdates = false;
    private JComboBox<AiModelProfile> activeProfileCombo;
    private DefaultComboBoxModel<AiModelProfile> activeProfileModel;
    private JComboBox<AiModelProfile> activeGitCommitProfileCombo;
    private DefaultComboBoxModel<AiModelProfile> activeGitCommitProfileModel;
    private JBLabel completionProfileHintLabel;
    private JBLabel gitCommitProfileHintLabel;
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
        content.add(buildProfileSection());
        content.add(Box.createVerticalStrut(12));
        content.add(buildFeatureSection());
        content.add(Box.createVerticalStrut(12));
        content.add(buildCompletionSection());
        content.add(Box.createVerticalStrut(12));
        content.add(buildPreferenceSection());
        content.add(Box.createVerticalGlue());
        return content;
    }

    private JPanel buildFeatureSection() {
        JPanel section = createSection(I18n.t("aiSettings.section.features"));
        codeCompletionEnabled = new JCheckBox(I18n.t("aiSettings.checkbox.codeCompletion"));
        codeCompletionEnabled.addActionListener(e -> updateFeatureAvailability());
        gitCommitMessageEnabled = new JCheckBox(I18n.t("aiSettings.checkbox.gitCommitMessage"));
        gitCommitMessageEnabled.addActionListener(e -> updateFeatureAvailability());
        section.add(checkBoxRow(codeCompletionEnabled));
        section.add(checkBoxRow(gitCommitMessageEnabled));

        JPanel gitLanguageRow = rowPanel();
        gitLanguageRow.add(new JBLabel(I18n.t("aiSettings.label.gitCommitLanguage")));
        gitCommitMessageLanguage = new JComboBox<>(AiFeatureSettings.GitCommitMessageLanguage.values());
        configureGitCommitLanguageCombo();
        gitLanguageRow.add(gitCommitMessageLanguage);
        section.add(gitLanguageRow);
        return section;
    }

    private void configureGitCommitLanguageCombo() {
        gitCommitMessageLanguage.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(
                JList<?> list,
                Object value,
                int index,
                boolean isSelected,
                boolean cellHasFocus
            ) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof AiFeatureSettings.GitCommitMessageLanguage language) {
                    setText(gitCommitLanguageDisplayName(language));
                }
                return this;
            }
        });
    }

    private String gitCommitLanguageDisplayName(AiFeatureSettings.GitCommitMessageLanguage language) {
        return switch (language) {
            case CHINESE -> I18n.t("aiSettings.gitCommitLanguage.chinese");
            case ENGLISH -> I18n.t("aiSettings.gitCommitLanguage.english");
            case JAPANESE -> I18n.t("aiSettings.gitCommitLanguage.japanese");
        };
    }

    private JPanel buildPreferenceSection() {
        JPanel section = createSection(I18n.t("settings.section.preferences"));

        JPanel languageRow = rowPanel();
        languageRow.add(new JBLabel(I18n.t("settings.label.uiLanguage")));
        uiLanguageCombo = new JComboBox<>(PluginSettings.Language.values());
        uiLanguageCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(
                JList<?> list,
                Object value,
                int index,
                boolean isSelected,
                boolean cellHasFocus
            ) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof PluginSettings.Language language) {
                    setText(language.getDisplayName(I18n.currentLanguage()));
                }
                return this;
            }
        });
        languageRow.add(uiLanguageCombo);
        section.add(languageRow);

        JPanel storageRow = rowPanel();
        storageRow.add(new JBLabel(I18n.t("settings.label.dataStorageMode")));
        storageModeCombo = new JComboBox<>(PluginSettings.DataStorageMode.values());
        storageModeCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(
                JList<?> list,
                Object value,
                int index,
                boolean isSelected,
                boolean cellHasFocus
            ) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof PluginSettings.DataStorageMode mode) {
                    setText(mode.getDisplayName(I18n.currentLanguage()));
                }
                return this;
            }
        });
        storageRow.add(storageModeCombo);
        JButton openStorageDirButton = new JButton(I18n.t("settings.button.openStorageDirectory"));
        openStorageDirButton.addActionListener(e ->
            openStorageDirectory((PluginSettings.DataStorageMode) storageModeCombo.getSelectedItem())
        );
        storageRow.add(openStorageDirButton);
        section.add(storageRow);
        return section;
    }

    private JPanel buildCompletionSection() {
        JPanel section = createSection(I18n.t("aiSettings.section.completion"));

        autoCompletionEnabled = new JCheckBox(I18n.t("aiSettings.checkbox.autoCompletion"));
        autoCompletionEnabled.addActionListener(e -> updateFeatureAvailability());
        section.add(checkBoxRow(autoCompletionEnabled));

        JPanel lengthRow = rowPanel();
        lengthRow.add(new JBLabel(I18n.t("aiSettings.label.autoLength")));
        autoCompletionLengthLevel = new JComboBox<>(AiCompletionLengthLevel.values());
        configureLengthLevelCombo(autoCompletionLengthLevel);
        lengthRow.add(autoCompletionLengthLevel);
        lengthRow.add(new JBLabel(I18n.t("aiSettings.label.manualLength")));
        manualCompletionLengthLevel = new JComboBox<>(AiCompletionLengthLevel.values());
        configureLengthLevelCombo(manualCompletionLengthLevel);
        lengthRow.add(manualCompletionLengthLevel);
        section.add(lengthRow);

        JPanel shortcutRow = rowPanel();
        shortcutRow.add(new JBLabel(I18n.t("aiSettings.label.manualShortcut")));
        manualShortcutField = new JBTextField(displayShortcutText(AiFeatureSettings.DEFAULT_MANUAL_SHORTCUT), 18);
        manualShortcutField.setEditable(false);
        setShortcutPlaceholder("");
        shortcutRow.add(manualShortcutField);
        JButton editShortcutButton = new JButton(I18n.t("common.button.edit"));
        editShortcutButton.addActionListener(e -> startShortcutCapture());
        shortcutRow.add(editShortcutButton);
        JButton resetShortcutButton = new JButton(I18n.t("common.button.reset"));
        resetShortcutButton.addActionListener(e -> resetManualShortcut());
        shortcutRow.add(resetShortcutButton);
        JBLabel hint = new JBLabel(I18n.t("aiSettings.hint.shortcut"));
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

    private void configureLengthLevelCombo(JComboBox<AiCompletionLengthLevel> comboBox) {
        comboBox.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(
                JList<?> list,
                Object value,
                int index,
                boolean isSelected,
                boolean cellHasFocus
            ) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof AiCompletionLengthLevel level) {
                    setText(lengthLevelDisplayName(level));
                }
                return this;
            }
        });
    }

    private String lengthLevelDisplayName(AiCompletionLengthLevel level) {
        return switch (level) {
            case SINGLE_LINE -> I18n.t("aiSettings.length.singleLine");
            case SHORT -> I18n.t("aiSettings.length.short");
            case MEDIUM -> I18n.t("aiSettings.length.medium");
            case LONG -> I18n.t("aiSettings.length.long");
        };
    }

    private JPanel buildProfileSection() {
        JPanel section = createSection(I18n.t("aiSettings.section.modelConfig"));
        String completionProfileLabel = I18n.t("aiSettings.label.completionProfile");
        String gitCommitProfileLabel = I18n.t("aiSettings.label.gitCommitProfile");
        int profileLabelWidth = Math.max(
            profileSelectionLabelWidth(completionProfileLabel),
            profileSelectionLabelWidth(gitCommitProfileLabel)
        );

        JPanel buttonRow = rowPanel();
        JButton configureButton = new JButton(I18n.t("aiSettings.button.modelConfig"), AllIcons.Actions.Edit);
        configureButton.addActionListener(e -> showProfileManagerDialog());
        buttonRow.add(configureButton);
        section.add(buttonRow);

        JPanel completionRow = profileSelectionRow();
        completionRow.add(profileSelectionLabel(completionProfileLabel, profileLabelWidth));
        activeProfileModel = new DefaultComboBoxModel<>();
        activeProfileCombo = new JComboBox<>(activeProfileModel);
        configureProfileCombo(activeProfileCombo);
        completionRow.add(activeProfileCombo);
        completionProfileHintLabel = profileHintLabel();
        completionRow.add(completionProfileHintLabel);
        section.add(completionRow);

        JPanel gitCommitRow = profileSelectionRow();
        gitCommitRow.add(profileSelectionLabel(gitCommitProfileLabel, profileLabelWidth));
        activeGitCommitProfileModel = new DefaultComboBoxModel<>();
        activeGitCommitProfileCombo = new JComboBox<>(activeGitCommitProfileModel);
        configureProfileCombo(activeGitCommitProfileCombo);
        gitCommitRow.add(activeGitCommitProfileCombo);
        gitCommitProfileHintLabel = profileHintLabel();
        gitCommitRow.add(gitCommitProfileHintLabel);
        section.add(gitCommitRow);

        return section;
    }

    private JPanel profileSelectionRow() {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        return row;
    }

    private int profileSelectionLabelWidth(String profileLabel) {
        return new JBLabel(profileLabel).getPreferredSize().width + JBUI.scale(10);
    }

    private JBLabel profileSelectionLabel(String text, int width) {
        JBLabel label = new JBLabel(text);
        label.setPreferredSize(new Dimension(width, label.getPreferredSize().height));
        label.setToolTipText(text);
        return label;
    }

    private JBLabel profileHintLabel() {
        JBLabel label = new JBLabel(" ");
        label.setForeground(JBColor.GRAY);
        label.setPreferredSize(new Dimension(JBUI.scale(180), label.getPreferredSize().height));
        return label;
    }

    private void configureProfileCombo(JComboBox<AiModelProfile> comboBox) {
        comboBox.setRenderer((JList<? extends AiModelProfile> list, AiModelProfile value, int index,
            boolean isSelected, boolean cellHasFocus) -> {
            JLabel label = new JLabel(value == null ? I18n.t("aiSettings.option.noProfile") : activeProfileDisplayName(value));
            label.setOpaque(true);
            label.setBackground(isSelected ? list.getSelectionBackground() : list.getBackground());
            label.setForeground(isSelected ? list.getSelectionForeground() : list.getForeground());
            label.setBorder(JBUI.Borders.empty(2, 6));
            return label;
        });
        comboBox.setPreferredSize(new Dimension(JBUI.scale(280), comboBox.getPreferredSize().height));
        comboBox.addActionListener(e -> {
            if (!suppressFeatureAvailabilityUpdates) {
                updateFeatureAvailability();
            }
        });
    }

    private void updateProfileHints() {
        updateProfileHint(
            completionProfileHintLabel,
            selectedProfile(activeProfileCombo),
            false,
            I18n.t("aiSettings.hint.useFimModel")
        );
        updateProfileHint(
            gitCommitProfileHintLabel,
            selectedProfile(activeGitCommitProfileCombo),
            true,
            I18n.t("aiSettings.hint.useLlmModel")
        );
    }

    private void updateProfileHint(JBLabel label, AiModelProfile profile, boolean showWhenFim, String text) {
        if (label == null) {
            return;
        }
        boolean shouldShow = profile != null && isFimFormat(profile.getFormat()) == showWhenFim;
        label.setText(shouldShow ? text : " ");
        label.setToolTipText(shouldShow ? text : null);
    }

    private boolean isFimFormat(AiModelFormat format) {
        return AiFeatureSettings.isNativeFimFormat(format);
    }

    private void startShortcutCapture() {
        removeShortcutCaptureListener();
        lastManualShortcut = normalizeShortcutText(manualShortcutField.getText()).isBlank()
            ? AiFeatureSettings.DEFAULT_MANUAL_SHORTCUT
            : normalizeShortcutText(manualShortcutField.getText());
        capturingShortcut = true;
        manualShortcutField.setText("");
        manualShortcutField.setForeground(UIManager.getColor("TextField.foreground"));
        setShortcutPlaceholder(I18n.t("aiSettings.placeholder.pressShortcut"));
        manualShortcutField.requestFocusInWindow();
        shortcutCaptureListener = new KeyAdapter() {
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
                removeShortcutCaptureListener();
            }
        };
        manualShortcutField.addKeyListener(shortcutCaptureListener);
    }

    private void resetManualShortcut() {
        removeShortcutCaptureListener();
        lastManualShortcut = normalizeShortcutText(AiFeatureSettings.DEFAULT_MANUAL_SHORTCUT);
        showShortcutValue(lastManualShortcut);
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
                selectProfile(activeProfileCombo, profile.getId());
                selectProfile(activeGitCommitProfileCombo, profile.getId());
            }
            updateFeatureAvailability();
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
        boolean firstProfile = profiles.isEmpty();
        profiles.add(profile);
        if (dialog.getApiKey() != null && !dialog.getApiKey().isBlank()) {
            editedApiKeys.put(profile.getId(), dialog.getApiKey());
        }
        reloadProfiles();
        if (firstProfile) {
            selectProfile(activeProfileCombo, profile.getId());
            selectProfile(activeGitCommitProfileCombo, profile.getId());
        }
        updateFeatureAvailability();
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
        updateFeatureAvailability();
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
        updateFeatureAvailability();
    }

    private void reloadProfiles() {
        Object activeSelection = activeProfileCombo == null ? null : activeProfileCombo.getSelectedItem();
        String activeId = activeSelection instanceof AiModelProfile activeProfile ? activeProfile.getId() : "";
        Object activeGitSelection = activeGitCommitProfileCombo == null ? null : activeGitCommitProfileCombo.getSelectedItem();
        String activeGitId = activeGitSelection instanceof AiModelProfile activeProfile ? activeProfile.getId() : "";
        suppressFeatureAvailabilityUpdates = true;
        try {
            if (profileTableModel != null) {
                profileTableModel.setRowCount(0);
            }
            if (activeProfileModel != null) {
                activeProfileModel.removeAllElements();
            }
            if (activeGitCommitProfileModel != null) {
                activeGitCommitProfileModel.removeAllElements();
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
                if (activeGitCommitProfileModel != null) {
                    activeGitCommitProfileModel.addElement(profile);
                }
            }
            selectProfile(activeProfileCombo, activeId);
            selectProfile(activeGitCommitProfileCombo, activeGitId);
            if (activeProfileModel != null && activeProfileModel.getSize() > 0 && activeProfileCombo.getSelectedItem() == null) {
                activeProfileCombo.setSelectedIndex(0);
            }
            if (activeGitCommitProfileModel != null
                && activeGitCommitProfileModel.getSize() > 0
                && activeGitCommitProfileCombo.getSelectedItem() == null) {
                activeGitCommitProfileCombo.setSelectedIndex(0);
            }
        } finally {
            suppressFeatureAvailabilityUpdates = false;
        }
    }

    private void selectProfile(JComboBox<AiModelProfile> comboBox, String profileId) {
        if (comboBox == null) {
            return;
        }
        for (AiModelProfile profile : profiles) {
            if (Objects.equals(profile.getId(), profileId)) {
                comboBox.setSelectedItem(profile);
                return;
            }
        }
    }

    private void updateFeatureAvailability() {
        updateProfileHints();
        boolean hasCompletionModel = hasConfiguredModel(activeProfileCombo);
        boolean hasGitCommitModel = hasConfiguredGitModel(activeGitCommitProfileCombo);

        if (codeCompletionEnabled != null) {
            codeCompletionEnabled.setEnabled(hasCompletionModel);
            if (!hasCompletionModel) {
                codeCompletionEnabled.setSelected(false);
            }
        }
        if (gitCommitMessageEnabled != null) {
            gitCommitMessageEnabled.setEnabled(hasGitCommitModel);
            if (!hasGitCommitModel) {
                gitCommitMessageEnabled.setSelected(false);
            }
        }
        if (gitCommitMessageLanguage != null) {
            gitCommitMessageLanguage.setEnabled(gitCommitMessageEnabled == null || gitCommitMessageEnabled.isSelected());
        }
        boolean completionEnabled = codeCompletionEnabled != null
            && codeCompletionEnabled.isEnabled()
            && codeCompletionEnabled.isSelected();
        if (autoCompletionEnabled != null) {
            autoCompletionEnabled.setEnabled(completionEnabled);
            if (!completionEnabled) {
                autoCompletionEnabled.setSelected(false);
            }
        }
    }

    private boolean hasConfiguredModel(JComboBox<AiModelProfile> comboBox) {
        AiModelProfile profile = selectedProfile(comboBox);
        return profile != null && !profile.getModel().isBlank();
    }

    private boolean hasConfiguredGitModel(JComboBox<AiModelProfile> comboBox) {
        AiModelProfile profile = selectedProfile(comboBox);
        return profile != null
            && !profile.getModel().isBlank()
            && AiFeatureSettings.supportsGitCommitFormat(profile.getFormat());
    }

    private AiModelProfile selectedProfile(JComboBox<AiModelProfile> comboBox) {
        if (comboBox == null) {
            return null;
        }
        Object selected = comboBox.getSelectedItem();
        return selected instanceof AiModelProfile profile ? profile : null;
    }

    @Override
    public boolean isModified() {
        AiFeatureSettings.State current = AiFeatureSettings.getInstance().snapshot();
        AiFeatureSettings.State next = collectState();
        PluginSettings settings = PluginSettings.getInstance();
        return !statesEqual(current, next)
            || selectedLanguage() != settings.getLanguage()
            || selectedStorageMode() != settings.getStorageMode()
            || !editedApiKeys.isEmpty()
            || !removedProfileIds.isEmpty();
    }

    @Override
    public void apply() throws ConfigurationException {
        String shortcut = capturingShortcut ? lastManualShortcut : normalizeShortcutText(manualShortcutField.getText().trim());
        if (shortcut.isBlank()) {
            shortcut = lastManualShortcut;
        }
        showShortcutValue(shortcut);
        if (!isTwoKeyShortcut(shortcut)) {
            throw new ConfigurationException(I18n.t("aiSettings.validation.shortcut"));
        }
        applyGlobalPreferences();
        AiFeatureSettings.State next = collectState();
        AiFeatureSettings.getInstance().update(next);
        for (String profileId : removedProfileIds) {
            AiFeatureSettings.getInstance().clearApiKey(profileId);
        }
        for (Map.Entry<String, String> entry : editedApiKeys.entrySet()) {
            AiFeatureSettings.getInstance().setApiKey(entry.getKey(), entry.getValue());
        }
        AiFeatureSettings.getInstance().backfillInlineApiKeysFromLegacyPasswordSafe(next);
        applyShortcut(shortcut);
        removedProfileIds.clear();
        editedApiKeys.clear();
    }

    @Override
    public void reset() {
        PluginSettings settings = PluginSettings.getInstance();
        uiLanguageCombo.setSelectedItem(settings.getLanguage());
        storageModeCombo.setSelectedItem(settings.getStorageMode());

        AiFeatureSettings.State state = AiFeatureSettings.getInstance().snapshot();
        codeCompletionEnabled.setSelected(state.codeCompletionEnabled);
        gitCommitMessageEnabled.setSelected(state.gitCommitMessageEnabled);
        gitCommitMessageLanguage.setSelectedItem(parseGitCommitLanguage(state.gitCommitMessageLanguage));
        autoCompletionEnabled.setSelected(state.autoCompletionEnabled);
        autoCompletionLengthLevel.setSelectedItem(parseLengthLevel(
            state.autoCompletionLengthLevel,
            AiCompletionLengthLevel.SHORT
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
        selectProfile(activeProfileCombo, state.activeCompletionProfileId);
        selectProfile(activeGitCommitProfileCombo, state.activeGitCommitProfileId);
        removedProfileIds.clear();
        editedApiKeys.clear();
        updateFeatureAvailability();
    }

    private AiCompletionLengthLevel parseLengthLevel(String value, AiCompletionLengthLevel fallback) {
        try {
            return AiCompletionLengthLevel.valueOf(value);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private void applyGlobalPreferences() throws ConfigurationException {
        PluginSettings.DataStorageMode currentStorageMode = PluginSettings.getInstance().getStorageMode();
        PluginSettings.DataStorageMode targetStorageMode = selectedStorageMode();
        if (targetStorageMode != currentStorageMode) {
            switchStorageMode(currentStorageMode, targetStorageMode);
        }

        PluginSettings.Language currentLanguage = PluginSettings.getInstance().getLanguage();
        PluginSettings.Language targetLanguage = selectedLanguage();
        if (targetLanguage != currentLanguage) {
            PluginSettings.getInstance().setLanguage(targetLanguage);
            int result = Messages.showYesNoDialog(
                I18n.t(
                    "settings.dialog.languageChanged.message",
                    targetLanguage.getDisplayName(I18n.currentLanguage())
                ),
                I18n.t("settings.dialog.languageChanged.title"),
                I18n.t("settings.dialog.languageChanged.restartNow"),
                I18n.t("settings.dialog.languageChanged.restartLater"),
                Messages.getQuestionIcon()
            );
            if (result == Messages.YES) {
                restartIdeAfterDialogs();
            }
        }
    }

    private void restartIdeAfterDialogs() {
        ApplicationManager.getApplication().invokeLater(
            () -> ApplicationManager.getApplication().restart(),
            ModalityState.nonModal()
        );
    }

    private void switchStorageMode(
        PluginSettings.DataStorageMode current,
        PluginSettings.DataStorageMode selected
    ) throws ConfigurationException {
        PluginStorageModeService.SharedDataStrategy strategy = null;
        if (selected == PluginSettings.DataStorageMode.USER_SHARED) {
            PluginStorageModeService.UserSharedInspection inspection =
                PluginStorageModeService.getInstance().inspectUserSharedState();
            if (inspection.hasSharedData()) {
                StorageConflictDialog dialog = new StorageConflictDialog(inspection);
                dialog.show();
                strategy = dialog.getSelectedStrategy();
                if (strategy == null) {
                    storageModeCombo.setSelectedItem(current);
                    throw new ConfigurationException(I18n.t("aiSettings.validation.storageCancelled"));
                }

                int detailedConfirm = Messages.showYesNoDialog(
                    buildStorageOverwriteConfirmMessage(strategy, inspection),
                    I18n.t("settings.dialog.storageMode.confirmTitle"),
                    I18n.t("settings.dialog.storageMode.confirmProceed"),
                    I18n.t("settings.dialog.storageMode.confirmNo"),
                    Messages.getQuestionIcon()
                );
                if (detailedConfirm != Messages.YES) {
                    storageModeCombo.setSelectedItem(current);
                    throw new ConfigurationException(I18n.t("aiSettings.validation.storageCancelled"));
                }
            } else {
                int confirm = Messages.showYesNoDialog(
                    I18n.t(
                        "settings.dialog.storageMode.confirm",
                        selected.getDisplayName(I18n.currentLanguage())
                    ),
                    I18n.t("settings.dialog.storageMode.confirmTitle"),
                    I18n.t("settings.dialog.storageMode.confirmYes"),
                    I18n.t("settings.dialog.storageMode.confirmNo"),
                    Messages.getQuestionIcon()
                );
                if (confirm != Messages.YES) {
                    storageModeCombo.setSelectedItem(current);
                    throw new ConfigurationException(I18n.t("aiSettings.validation.storageCancelled"));
                }
            }
        } else {
            int confirm = Messages.showYesNoDialog(
                I18n.t(
                    "settings.dialog.storageMode.confirmBackToLocal",
                    selected.getDisplayName(I18n.currentLanguage())
                ),
                I18n.t("settings.dialog.storageMode.confirmTitle"),
                I18n.t("settings.dialog.storageMode.confirmYes"),
                I18n.t("settings.dialog.storageMode.confirmNo"),
                Messages.getQuestionIcon()
            );
            if (confirm != Messages.YES) {
                storageModeCombo.setSelectedItem(current);
                throw new ConfigurationException(I18n.t("aiSettings.validation.storageCancelled"));
            }
        }

        PluginStorageModeService.SwitchResult result =
            PluginStorageModeService.getInstance().switchMode(selected, strategy);
        if (!result.success()) {
            storageModeCombo.setSelectedItem(current);
            throw new ConfigurationException(I18n.t("settings.dialog.storageMode.failed"));
        }
        Messages.showInfoMessage(
            I18n.t(
                "settings.dialog.storageMode.applied",
                selected.getDisplayName(I18n.currentLanguage())
            ),
            I18n.t("settings.dialog.storageMode.appliedTitle")
        );
    }

    private PluginSettings.Language selectedLanguage() {
        Object selected = uiLanguageCombo == null ? null : uiLanguageCombo.getSelectedItem();
        return selected instanceof PluginSettings.Language language
            ? language
            : PluginSettings.getInstance().getLanguage();
    }

    private PluginSettings.DataStorageMode selectedStorageMode() {
        Object selected = storageModeCombo == null ? null : storageModeCombo.getSelectedItem();
        return selected instanceof PluginSettings.DataStorageMode mode
            ? mode
            : PluginSettings.getInstance().getStorageMode();
    }

    private void openStorageDirectory(PluginSettings.DataStorageMode mode) {
        PluginSettings.DataStorageMode targetMode =
            mode != null ? mode : PluginSettings.getInstance().getStorageMode();
        Path directory = targetMode == PluginSettings.DataStorageMode.USER_SHARED
            ? PluginDataStorage.getUserSharedRootDir()
            : Path.of(PathManager.getOptionsPath());

        try {
            Files.createDirectories(directory);
            if (!Desktop.isDesktopSupported()) {
                throw new IOException("desktop-not-supported");
            }
            Desktop.getDesktop().open(directory.toFile());
        } catch (Exception ex) {
            Messages.showErrorDialog(
                I18n.t("settings.dialog.storageDirectory.openFailed", directory.toString()),
                I18n.t("settings.section.storageLocation")
            );
        }
    }

    private String buildStorageOverwriteConfirmMessage(
        PluginStorageModeService.SharedDataStrategy strategy,
        PluginStorageModeService.UserSharedInspection inspection
    ) {
        return switch (strategy) {
            case LOCAL_TO_SHARED -> I18n.t(
                "settings.dialog.storageMode.confirmLocalToShared",
                inspection.localSummary().totalCount(),
                inspection.localSummary().providerCount(),
                inspection.localSummary().promptCount(),
                inspection.localSummary().skillCount(),
                inspection.localSummary().mcpCount(),
                inspection.localSummary().aiFeatureCount()
            );
            case SHARED_TO_LOCAL -> I18n.t(
                "settings.dialog.storageMode.confirmSharedToLocal",
                inspection.sharedSummary().totalCount(),
                inspection.sharedSummary().providerCount(),
                inspection.sharedSummary().promptCount(),
                inspection.sharedSummary().skillCount(),
                inspection.sharedSummary().mcpCount(),
                inspection.sharedSummary().aiFeatureCount()
            );
        };
    }

    @Override
    public void disposeUIResources() {
        removeShortcutCaptureListener();
        rootPanel = null;
    }

    private AiFeatureSettings.State collectState() {
        AiFeatureSettings.State state = AiFeatureSettings.getInstance().snapshot();
        state.codeCompletionEnabled = codeCompletionEnabled != null && codeCompletionEnabled.isSelected();
        state.gitCommitMessageEnabled = gitCommitMessageEnabled != null && gitCommitMessageEnabled.isSelected();
        state.gitCommitMessageLanguage = selectedGitCommitLanguageName();
        state.autoCompletionEnabled = autoCompletionEnabled != null && autoCompletionEnabled.isSelected();
        state.autoCompletionLengthLevel = selectedLengthName(
            autoCompletionLengthLevel,
            AiCompletionLengthLevel.SHORT
        );
        state.manualCompletionLengthLevel = selectedLengthName(
            manualCompletionLengthLevel,
            AiCompletionLengthLevel.MEDIUM
        );
        state.manualCompletionShortcut = manualShortcutField == null
            ? AiFeatureSettings.DEFAULT_MANUAL_SHORTCUT
            : manualShortcutValue();
        Object selected = activeProfileCombo == null ? null : activeProfileCombo.getSelectedItem();
        state.activeCompletionProfileId = selected instanceof AiModelProfile completionProfile ? completionProfile.getId() : "";
        Object selectedGitCommitProfile = activeGitCommitProfileCombo == null ? null : activeGitCommitProfileCombo.getSelectedItem();
        state.activeGitCommitProfileId = selectedGitCommitProfile instanceof AiModelProfile gitCommitProfile
            ? gitCommitProfile.getId()
            : "";
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
            && Objects.equals(a.gitCommitMessageLanguage, b.gitCommitMessageLanguage)
            && a.autoCompletionEnabled == b.autoCompletionEnabled
            && Objects.equals(a.autoCompletionLengthLevel, b.autoCompletionLengthLevel)
            && Objects.equals(a.manualCompletionLengthLevel, b.manualCompletionLengthLevel)
            && Objects.equals(a.activeCompletionProfileId, b.activeCompletionProfileId)
            && Objects.equals(a.activeGitCommitProfileId, b.activeGitCommitProfileId)
            && Objects.equals(a.manualCompletionShortcut, b.manualCompletionShortcut)
            && Objects.equals(a.timingConfig, b.timingConfig)
            && Objects.equals(a.profiles, b.profiles);
    }

    private String manualShortcutValue() {
        if (capturingShortcut) {
            return lastManualShortcut;
        }
        String value = normalizeShortcutText(manualShortcutField.getText());
        return value.isBlank() ? lastManualShortcut : value;
    }

    private AiFeatureSettings.GitCommitMessageLanguage parseGitCommitLanguage(String value) {
        try {
            return AiFeatureSettings.GitCommitMessageLanguage.valueOf(value);
        } catch (Exception ignored) {
            return AiFeatureSettings.GitCommitMessageLanguage.CHINESE;
        }
    }

    private String selectedGitCommitLanguageName() {
        Object selected = gitCommitMessageLanguage == null ? null : gitCommitMessageLanguage.getSelectedItem();
        return selected instanceof AiFeatureSettings.GitCommitMessageLanguage language
            ? language.name()
            : AiFeatureSettings.GitCommitMessageLanguage.CHINESE.name();
    }

    private void showShortcutValue(String shortcut) {
        capturingShortcut = false;
        manualShortcutField.setText(displayShortcutText(shortcut));
        manualShortcutField.setForeground(UIManager.getColor("TextField.foreground"));
        setShortcutPlaceholder("");
    }

    private void setShortcutPlaceholder(String text) {
        if (manualShortcutField instanceof JBTextField field) {
            field.getEmptyText().setText(text);
        }
    }

    private void removeShortcutCaptureListener() {
        if (shortcutCaptureListener != null && manualShortcutField != null) {
            manualShortcutField.removeKeyListener(shortcutCaptureListener);
            shortcutCaptureListener = null;
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
        for (String actionId : keymap.getActionIdList(targetShortcut)) {
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
            setTitle(I18n.t("aiSettings.section.modelConfig"));
            init();
        }

        @Override
        protected @Nullable JComponent createCenterPanel() {
            JPanel panel = new JPanel(new BorderLayout(0, 8));
            panel.setBorder(JBUI.Borders.empty(8));

            String[] columns = {
                I18n.t("aiSettings.table.name"),
                I18n.t("aiSettings.table.format"),
                "Base URL",
                I18n.t("aiSettings.table.model")
            };
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

            JBLabel completionModelHint = new JBLabel(I18n.t("aiSettings.hint.fimModelRecommended"));
            completionModelHint.setForeground(JBColor.GRAY);

            JPanel buttonRow = rowPanel();
            JButton addButton = new JButton(I18n.t("common.button.add"), AllIcons.General.Add);
            addButton.addActionListener(e -> addProfile());
            buttonRow.add(addButton);

            JButton editButton = new JButton(I18n.t("common.button.edit"), AllIcons.Actions.Edit);
            editButton.addActionListener(e -> editSelectedProfile());
            buttonRow.add(editButton);

            JButton removeButton = new JButton(I18n.t("common.button.delete"), AllIcons.General.Remove);
            removeButton.addActionListener(e -> removeSelectedProfile());
            buttonRow.add(removeButton);

            JPanel southPanel = new JPanel();
            southPanel.setLayout(new BoxLayout(southPanel, BoxLayout.Y_AXIS));
            completionModelHint.setAlignmentX(Component.LEFT_ALIGNMENT);
            buttonRow.setAlignmentX(Component.LEFT_ALIGNMENT);
            southPanel.add(completionModelHint);
            southPanel.add(Box.createVerticalStrut(8));
            southPanel.add(buttonRow);
            panel.add(southPanel, BorderLayout.SOUTH);

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
        private JComboBox<Boolean> fimEnabledCombo;
        private JBLabel completionModelHint;
        private JButton testButton;
        private JButton detectModelsButton;
        private JBLabel testStatusLabel;
        private String latestTestDetail = "";
        private boolean testStatusClickable = false;

        private ProfileDialog(AiModelProfile profile, String existingApiKey) {
            super(true);
            this.original = profile == null ? new AiModelProfile() : profile.copy();
            this.existingApiKey = existingApiKey == null ? "" : existingApiKey;
            setTitle(profile == null ? I18n.t("aiSettings.dialog.addProfile") : I18n.t("aiSettings.dialog.editProfile"));
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
            fimEnabledCombo = new JComboBox<>(new Boolean[] { false, true });
            fimEnabledCombo.setSelectedItem(original.isFimEnabled());
            configureFimEnabledCombo();
            formatCombo.addActionListener(e -> updateFormatDependentUi());

            FormBuilder form = FormBuilder.createFormBuilder()
                .addLabeledComponent(I18n.t("aiSettings.label.profileName"), nameField)
                .addLabeledComponent(I18n.t("aiSettings.label.protocolFormat"), formatCombo)
                .addLabeledComponent("Base URL:", baseUrlField)
                .addLabeledComponent(I18n.t("aiSettings.label.model"), modelField)
                .addLabeledComponent("API Key:", apiKeyField)
                .addLabeledComponent(I18n.t("aiSettings.label.timeoutSeconds"), timeoutSpinner)
                .addLabeledComponent(I18n.t("aiSettings.label.fimAdapterEnabled"), fimEnabledCombo)
                .addLabeledComponent(I18n.t("aiSettings.label.customHeaders"), new JBScrollPane(headersArea));

            completionModelHint = new JBLabel(I18n.t("aiSettings.hint.fimModelRecommended"));
            completionModelHint.setForeground(JBColor.GRAY);

            JPanel buttonRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
            testButton = new JButton(I18n.t("aiSettings.button.testConfig"));
            testButton.addActionListener(e -> runConnectionTest());
            buttonRow.add(testButton);

            detectModelsButton = new JButton(I18n.t("aiSettings.button.detectModels"));
            detectModelsButton.addActionListener(e -> detectModels());
            buttonRow.add(detectModelsButton);

            testStatusLabel = new JBLabel(" ");
            testStatusLabel.setForeground(JBColor.GRAY);
            testStatusLabel.setPreferredSize(new Dimension(JBUI.scale(220), testStatusLabel.getPreferredSize().height));
            testStatusLabel.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (testStatusClickable && SwingUtilities.isLeftMouseButton(e)) {
                        showTestDetail();
                    }
                }
            });
            buttonRow.add(testStatusLabel);

            JPanel panel = new JPanel(new BorderLayout(0, 10));
            panel.setBorder(JBUI.Borders.empty(8));
            JComponent formPanel = form.getPanel();
            formPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
            panel.add(formPanel, BorderLayout.CENTER);
            JPanel southPanel = new JPanel();
            southPanel.setLayout(new BoxLayout(southPanel, BoxLayout.Y_AXIS));
            completionModelHint.setAlignmentX(Component.LEFT_ALIGNMENT);
            buttonRow.setAlignmentX(Component.LEFT_ALIGNMENT);
            southPanel.add(completionModelHint);
            southPanel.add(Box.createVerticalStrut(8));
            southPanel.add(buttonRow);
            panel.add(southPanel, BorderLayout.SOUTH);
            updateFormatDependentUi();
            panel.setPreferredSize(new Dimension(JBUI.scale(620), JBUI.scale(430)));
            return panel;
        }

        @Override
        protected @Nullable ValidationInfo doValidate() {
            if (modelField.getText().trim().isBlank()) {
                return new ValidationInfo(I18n.t("aiSettings.validation.modelRequired"), modelField);
            }
            if (baseUrlField.getText().trim().isBlank()) {
                return new ValidationInfo(I18n.t("aiSettings.validation.baseUrlRequired"), baseUrlField);
            }
            String headers = headersArea.getText().trim();
            if (!headers.isBlank()) {
                try {
                    if (!JsonParser.parseString(headers).isJsonObject()) {
                        return new ValidationInfo(I18n.t("aiSettings.validation.headersObject"), headersArea);
                    }
                } catch (Exception ex) {
                    return new ValidationInfo(I18n.t("aiSettings.validation.headersInvalid", ex.getMessage()), headersArea);
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
            profile.setApiKey(getApiKey());
            profile.setTimeoutSeconds((Integer) timeoutSpinner.getValue());
            profile.setHeadersJson(normalizeHeaders(headersArea.getText()));
            profile.setFimEnabled(Boolean.TRUE.equals(fimEnabledCombo.getSelectedItem())
                && !AiFeatureSettings.isNativeFimFormat(profile.getFormat()));
            profile.setFimPrefixToken("");
            profile.setFimSuffixToken("");
            profile.setFimMiddleToken("");
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

        private void configureFimEnabledCombo() {
            fimEnabledCombo.setRenderer(new DefaultListCellRenderer() {
                @Override
                public Component getListCellRendererComponent(
                    JList<?> list,
                    Object value,
                    int index,
                    boolean isSelected,
                    boolean cellHasFocus
                ) {
                    super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                    setText(Boolean.TRUE.equals(value)
                        ? I18n.t("settings.option.enabled")
                        : I18n.t("settings.option.disabled"));
                    return this;
                }
            });
        }

        private void updateFormatDependentUi() {
            AiModelFormat selected = (AiModelFormat) formatCombo.getSelectedItem();
            boolean nativeFim = AiFeatureSettings.isNativeFimFormat(selected);
            if (nativeFim) {
                fimEnabledCombo.setSelectedItem(false);
            }
            fimEnabledCombo.setEnabled(!nativeFim);
            completionModelHint.setText(I18n.t("aiSettings.hint.fimModelRecommended"));
            completionModelHint.setToolTipText(I18n.t("aiSettings.hint.fimModelRecommended"));
        }

        private void runConnectionTest() {
            AiModelProfile profile;
            try {
                profile = getProfile();
            } catch (Exception ex) {
                setTestStatus(I18n.t("aiSettings.status.invalidConfig", ex.getMessage()), false);
                return;
            }
            String apiKey = getApiKey();
            setTestingButtonsEnabled(false);
            setTestStatus(I18n.t("aiSettings.status.testing"), true);

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
                setTestStatus(I18n.t("aiSettings.status.invalidConfig", ex.getMessage()), false);
                return;
            }
            String apiKey = getApiKey();
            setTestingButtonsEnabled(false);
            setTestStatus(I18n.t("aiSettings.status.detectingModels"), true);

            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                AiModelConnectionTestService.ModelListResult result =
                    AiModelConnectionTestService.getInstance().listModels(profile, apiKey);
                SwingUtilities.invokeLater(() -> {
                    setTestingButtonsEnabled(true);
                    if (!result.success()) {
                        setTestStatus(result.message() + " (" + result.durationMs() + " ms)", false);
                        return;
                    }
                    setTestStatus(I18n.t("aiSettings.status.modelsDetected", result.models().size(), result.durationMs()), true);
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
            testStatusClickable = !success && !normalized.isBlank();
            testStatusLabel.setCursor(Cursor.getPredefinedCursor(
                testStatusClickable ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR
            ));
        }

        private String shortStatus(String message, boolean success) {
            if (message == null || message.isBlank()) {
                return " ";
            }
            if (success) {
                return truncateStatus(message);
            }
            return I18n.t("aiSettings.status.failedClickDetails");
        }

        private String truncateStatus(String message) {
            int max = 36;
            return message.length() <= max ? message : message.substring(0, max - 3) + "...";
        }

        private void showTestDetail() {
            if (latestTestDetail == null || latestTestDetail.isBlank()) {
                return;
            }
            Messages.showErrorDialog(latestTestDetail, I18n.t("aiSettings.dialog.modelCheckFailed"));
        }
    }

    private static final class ModelSelectionDialog extends DialogWrapper {

        private final List<String> models;
        private JList<String> modelList;

        private ModelSelectionDialog(List<String> models) {
            super(true);
            this.models = models;
            setTitle(I18n.t("aiSettings.dialog.detectedModels"));
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

    private static final class StorageConflictDialog extends DialogWrapper {

        private final PluginStorageModeService.UserSharedInspection inspection;
        private PluginStorageModeService.SharedDataStrategy selectedStrategy;
        private JComponent preferredFocus;

        private StorageConflictDialog(PluginStorageModeService.UserSharedInspection inspection) {
            super(true);
            this.inspection = inspection;
            setTitle(I18n.t("settings.dialog.storageMode.detectTitle"));
            setResizable(false);
            init();
        }

        @Override
        protected @Nullable JComponent createCenterPanel() {
            JPanel panel = new JPanel(new BorderLayout(0, 12));
            panel.setBorder(JBUI.Borders.empty(8, 4));

            JTextArea textArea = new JTextArea(
                I18n.t(
                    "settings.dialog.storageMode.conflict",
                    inspection.localSummary().totalCount(),
                    inspection.sharedSummary().totalCount(),
                    inspection.localSummary().providerCount(),
                    inspection.localSummary().promptCount(),
                    inspection.localSummary().skillCount(),
                    inspection.localSummary().mcpCount(),
                    inspection.sharedSummary().providerCount(),
                    inspection.sharedSummary().promptCount(),
                    inspection.sharedSummary().skillCount(),
                    inspection.sharedSummary().mcpCount(),
                    inspection.localSummary().aiFeatureCount(),
                    inspection.sharedSummary().aiFeatureCount()
                )
            );
            textArea.setEditable(false);
            textArea.setLineWrap(true);
            textArea.setWrapStyleWord(true);
            textArea.setOpaque(false);
            textArea.setBorder(JBUI.Borders.empty());
            textArea.setFocusable(false);

            JBScrollPane scrollPane = new JBScrollPane(textArea);
            scrollPane.setBorder(JBUI.Borders.empty());
            scrollPane.setPreferredSize(new Dimension(JBUI.scale(420), JBUI.scale(170)));
            preferredFocus = scrollPane;
            panel.add(scrollPane, BorderLayout.CENTER);
            return panel;
        }

        @Override
        protected @Nullable JComponent createSouthPanel() {
            JPanel panel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
            panel.setBorder(JBUI.Borders.empty(8, 0, 0, 0));
            panel.add(createChoiceButton(
                I18n.t("settings.dialog.storageMode.option.localToShared"),
                PluginStorageModeService.SharedDataStrategy.LOCAL_TO_SHARED
            ));
            panel.add(createChoiceButton(
                I18n.t("settings.dialog.storageMode.option.sharedToLocal"),
                PluginStorageModeService.SharedDataStrategy.SHARED_TO_LOCAL
            ));

            JButton cancelButton = new JButton(I18n.t("settings.dialog.storageMode.confirmNo"));
            cancelButton.setDefaultCapable(false);
            cancelButton.addActionListener(e -> doCancelAction());
            panel.add(cancelButton);
            return panel;
        }

        private JButton createChoiceButton(String text, PluginStorageModeService.SharedDataStrategy strategy) {
            JButton button = new JButton(text);
            button.setDefaultCapable(false);
            button.addActionListener(e -> {
                selectedStrategy = strategy;
                close(OK_EXIT_CODE);
            });
            return button;
        }

        @Override
        public @Nullable JComponent getPreferredFocusedComponent() {
            return preferredFocus;
        }

        @Override
        protected Action[] createActions() {
            return new Action[0];
        }

        private PluginStorageModeService.SharedDataStrategy getSelectedStrategy() {
            return selectedStrategy;
        }
    }
}
