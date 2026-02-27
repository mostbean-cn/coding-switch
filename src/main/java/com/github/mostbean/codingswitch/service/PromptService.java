package com.github.mostbean.codingswitch.service;

import com.github.mostbean.codingswitch.model.CliType;
import com.github.mostbean.codingswitch.model.PromptPreset;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 提示词预设管理服务。
 * 管理系统提示词预设，激活时写入对应 CLI 的提示词文件。
 */
@Service(Service.Level.APP)
@State(name = "CodingSwitchPrompts", storages = @Storage("coding-switch-prompts.xml"))
public final class PromptService implements PersistentStateComponent<PromptService.State> {

    private static final Logger LOG = Logger.getInstance(PromptService.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static class State {
        public String presetsJson = "[]";
    }

    private State myState = new State();
    private final List<Runnable> changeListeners = new ArrayList<>();

    public static PromptService getInstance() {
        return ApplicationManager.getApplication().getService(PromptService.class);
    }

    @Override
    public @Nullable State getState() {
        myState.presetsJson = GSON.toJson(getPresets());
        return myState;
    }

    @Override
    public void loadState(@NotNull State state) {
        myState = state;
    }

    // =====================================================================
    // CRUD
    // =====================================================================

    public List<PromptPreset> getPresets() {
        try {
            List<PromptPreset> list = GSON.fromJson(myState.presetsJson,
                    new TypeToken<List<PromptPreset>>() {
                    }.getType());
            return list != null ? list : new ArrayList<>();
        } catch (Exception e) {
            LOG.warn("Failed to parse prompts", e);
            return new ArrayList<>();
        }
    }

    public List<PromptPreset> getPresetsByType(CliType cliType) {
        return getPresets().stream()
                .filter(p -> p.getTargetCli() == cliType)
                .toList();
    }

    public Optional<PromptPreset> getActivePreset(CliType cliType) {
        return getPresets().stream()
                .filter(p -> p.getTargetCli() == cliType && p.isActive())
                .findFirst();
    }

    public void addPreset(PromptPreset preset) {
        List<PromptPreset> presets = new ArrayList<>(getPresets());
        presets.add(preset);
        savePresets(presets);
    }

    public void updatePreset(PromptPreset preset) {
        List<PromptPreset> presets = new ArrayList<>(getPresets());
        presets.replaceAll(p -> p.getId().equals(preset.getId()) ? preset : p);
        savePresets(presets);
    }

    public void removePreset(String presetId) {
        List<PromptPreset> presets = new ArrayList<>(getPresets());
        presets.removeIf(p -> p.getId().equals(presetId));
        savePresets(presets);
    }

    // =====================================================================
    // 激活预设（写入文件）
    // =====================================================================

    /**
     * 激活指定预设并将其内容写入对应 CLI 的提示词文件。
     * 激活前自动回填保护：保存当前文件内容到之前激活的预设。
     */
    public void activatePreset(String presetId) throws IOException {
        List<PromptPreset> presets = new ArrayList<>(getPresets());
        PromptPreset target = null;

        for (PromptPreset p : presets) {
            if (p.getId().equals(presetId)) {
                target = p;
                break;
            }
        }

        if (target == null) {
            throw new IllegalArgumentException("Preset not found: " + presetId);
        }

        ConfigFileService configService = ConfigFileService.getInstance();
        CliType cliType = target.getTargetCli();

        // 回填保护：将当前文件内容保存回之前的 active 预设
        // 但如果目标就是当前已激活的预设，则跳过回填（用户已显式编辑了内容）
        backfillCurrentContent(presets, cliType, configService, presetId);

        // 同一 CLI 类型下只能有一个 active
        for (PromptPreset p : presets) {
            if (p.getTargetCli() == cliType) {
                p.setActive(p.getId().equals(presetId));
            }
        }

        // 写入文件
        Path promptPath = configService.getPromptFilePath(cliType);
        if (cliType == CliType.OPENCODE) {
            // OpenCode 写到 agents 目录下的 default.md
            Path agentFile = promptPath.resolve("default.md");
            configService.writeFile(agentFile, target.getContent());
        } else {
            configService.writeFile(promptPath, target.getContent());
        }

        savePresets(presets);
    }

    /**
     * 从对应 CLI 的提示词文件中读取当前内容。
     */
    public String readCurrentPrompt(CliType cliType) {
        ConfigFileService configService = ConfigFileService.getInstance();
        Path promptPath = configService.getPromptFilePath(cliType);
        if (cliType == CliType.OPENCODE) {
            Path agentFile = promptPath.resolve("default.md");
            return configService.readFile(agentFile);
        }
        return configService.readFile(promptPath);
    }

    /**
     * 回填保护：将当前文件内容保存回之前 active 的预设。
     * 如果 excludePresetId 非空且与当前 active 预设相同，则跳过回填（用户已显式编辑了内容）。
     */
    private void backfillCurrentContent(List<PromptPreset> presets, CliType cliType,
            ConfigFileService svc, String excludePresetId) {
        for (PromptPreset p : presets) {
            if (p.getTargetCli() == cliType && p.isActive()) {
                // 如果要激活的就是当前 active 的预设，跳过回填
                if (p.getId().equals(excludePresetId)) {
                    break;
                }
                String currentContent = readCurrentPrompt(cliType);
                if (!currentContent.isBlank()) {
                    p.setContent(currentContent);
                }
                break;
            }
        }
    }

    // =====================================================================
    // 内部工具
    // =====================================================================

    private void savePresets(List<PromptPreset> presets) {
        myState.presetsJson = GSON.toJson(presets);
        fireChanged();
    }

    public void addChangeListener(Runnable listener) {
        changeListeners.add(listener);
    }

    private void fireChanged() {
        for (Runnable listener : changeListeners) {
            listener.run();
        }
    }
}
