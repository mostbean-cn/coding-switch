package com.github.mostbean.codingswitch.service;

import com.github.mostbean.codingswitch.model.McpServer;
import com.github.mostbean.codingswitch.model.PromptPreset;
import com.github.mostbean.codingswitch.model.Provider;
import com.github.mostbean.codingswitch.model.Skill;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;

import java.util.ArrayList;
import java.util.List;

@Service(Service.Level.APP)
public final class PluginStorageModeService {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public enum SharedDataStrategy {
        LOCAL_TO_SHARED,
        SHARED_TO_LOCAL
    }

    public record DataSummary(int providerCount, int promptCount, int skillCount, int mcpCount, int aiFeatureCount) {
        public int totalCount() {
            return providerCount + promptCount + skillCount + mcpCount + aiFeatureCount;
        }

        public boolean hasAnyData() {
            return totalCount() > 0;
        }
    }

    public record UserSharedInspection(boolean hasSharedData, DataSummary localSummary, DataSummary sharedSummary) {
    }

    public record SwitchResult(boolean success, boolean restartRequired, String message) {
    }

    public static PluginStorageModeService getInstance() {
        return ApplicationManager.getApplication().getService(PluginStorageModeService.class);
    }

    public synchronized UserSharedInspection inspectUserSharedState() {
        ProviderService providerService = ProviderService.getInstance();
        PromptService promptService = PromptService.getInstance();
        SkillService skillService = SkillService.getInstance();
        McpService mcpService = McpService.getInstance();
        AiFeatureSettings aiFeatureSettings = AiFeatureSettings.getInstance();

        DataSummary localSummary = new DataSummary(
                countProviders(providerService.snapshotLocalState()),
                countPrompts(promptService.snapshotLocalState()),
                countSkills(skillService.snapshotLocalState()),
                countMcpServers(mcpService.snapshotLocalState()),
                countAiFeatures(aiFeatureSettings.snapshotLocalState()));

        DataSummary sharedSummary = new DataSummary(
                countProviders(providerService.snapshotSharedState()),
                countPrompts(promptService.snapshotSharedState()),
                countSkills(skillService.snapshotSharedState()),
                countMcpServers(mcpService.snapshotSharedState()),
                countAiFeatures(aiFeatureSettings.snapshotSharedState()));

        return new UserSharedInspection(sharedSummary.hasAnyData(), localSummary, sharedSummary);
    }

    public synchronized SwitchResult switchMode(PluginSettings.DataStorageMode targetMode,
            SharedDataStrategy sharedDataStrategy) {
        PluginSettings settings = PluginSettings.getInstance();
        PluginSettings.DataStorageMode currentMode = settings.getStorageMode();
        if (targetMode == null) {
            return new SwitchResult(false, false, "invalid-target");
        }
        if (currentMode == targetMode) {
            return new SwitchResult(true, false, "no-change");
        }

        ProviderService providerService = ProviderService.getInstance();
        PromptService promptService = PromptService.getInstance();
        SkillService skillService = SkillService.getInstance();
        McpService mcpService = McpService.getInstance();
        AiFeatureSettings aiFeatureSettings = AiFeatureSettings.getInstance();

        if (targetMode == PluginSettings.DataStorageMode.USER_SHARED) {
            UserSharedInspection inspection = inspectUserSharedState();
            SharedDataStrategy strategy = inspection.hasSharedData()
                    ? sharedDataStrategy
                    : SharedDataStrategy.LOCAL_TO_SHARED;
            if (strategy == null) {
                return new SwitchResult(false, false, "shared-data-strategy-required");
            }

            if (strategy == SharedDataStrategy.LOCAL_TO_SHARED) {
                PluginSettings.State settingsSnapshot = settings.snapshotLocalState();
                settingsSnapshot.storageMode = PluginSettings.DataStorageMode.USER_SHARED.name();
                settings.writeSharedState(settingsSnapshot);
                providerService.writeSharedState(providerService.snapshotLocalState());
                promptService.writeSharedState(promptService.snapshotLocalState());
                skillService.writeSharedState(skillService.snapshotLocalState());
                mcpService.writeSharedState(mcpService.snapshotLocalState());
                aiFeatureSettings.writeSharedState(aiFeatureSettings.snapshotLocalState());
            } else {
                PluginSettings.State sharedSettings = settings.snapshotSharedState();
                sharedSettings.storageMode = PluginSettings.DataStorageMode.IDE_LOCAL.name();
                settings.overwriteLocalState(sharedSettings);
                providerService.overwriteLocalState(providerService.snapshotSharedState());
                promptService.overwriteLocalState(promptService.snapshotSharedState());
                skillService.overwriteLocalState(skillService.snapshotSharedState());
                mcpService.overwriteLocalState(mcpService.snapshotSharedState());
                aiFeatureSettings.overwriteLocalState(aiFeatureSettings.snapshotSharedState());
            }
        } else {
            PluginSettings.State settingsSnapshot = settings.snapshotCurrentState();
            settingsSnapshot.storageMode = PluginSettings.DataStorageMode.IDE_LOCAL.name();
            settings.overwriteLocalState(settingsSnapshot);
            providerService.overwriteLocalState(providerService.snapshotCurrentState());
            promptService.overwriteLocalState(promptService.snapshotCurrentState());
            skillService.overwriteLocalState(skillService.snapshotCurrentState());
            mcpService.overwriteLocalState(mcpService.snapshotCurrentState());
            aiFeatureSettings.overwriteLocalState(aiFeatureSettings.snapshotCurrentState());
        }

        settings.setLocalStorageMode(targetMode);
        providerService.notifyStateChanged();
        promptService.notifyStateChanged();
        skillService.notifyStateChanged();
        mcpService.notifyStateChanged();
        aiFeatureSettings.notifyStateChanged();

        return new SwitchResult(true, false, targetMode.name());
    }

    private static int countProviders(ProviderService.State state) {
        return parseListSize(state != null ? state.providersJson : "[]", new TypeToken<List<Provider>>() {
        }.getType());
    }

    private static int countPrompts(PromptService.State state) {
        return parseListSize(state != null ? state.presetsJson : "[]", new TypeToken<List<PromptPreset>>() {
        }.getType());
    }

    private static int countSkills(SkillService.State state) {
        return parseListSize(state != null ? state.skillsJson : "[]", new TypeToken<List<Skill>>() {
        }.getType());
    }

    private static int countMcpServers(McpService.StateData state) {
        return parseListSize(state != null ? state.serversJson : "[]", new TypeToken<List<McpServer>>() {
        }.getType());
    }

    private static int countAiFeatures(AiFeatureSettings.State state) {
        AiFeatureSettings.State normalized = AiFeatureSettings.normalize(AiFeatureSettings.copyState(state));
        AiFeatureSettings.State empty = AiFeatureSettings.normalize(new AiFeatureSettings.State());
        boolean configured = normalized.codeCompletionEnabled != empty.codeCompletionEnabled
                || normalized.gitCommitMessageEnabled != empty.gitCommitMessageEnabled
                || normalized.autoCompletionEnabled != empty.autoCompletionEnabled
                || normalized.projectContextEnabled != empty.projectContextEnabled
                || normalized.autoCompletionMaxTokens != empty.autoCompletionMaxTokens
                || normalized.manualCompletionMaxTokens != empty.manualCompletionMaxTokens
                || !java.util.Objects.equals(normalized.timingConfig, empty.timingConfig)
                || !java.util.Objects.equals(normalized.autoCompletionLengthLevel, empty.autoCompletionLengthLevel)
                || !java.util.Objects.equals(normalized.manualCompletionLengthLevel, empty.manualCompletionLengthLevel)
                || !java.util.Objects.equals(normalized.activeCompletionProfileId, empty.activeCompletionProfileId)
                || !java.util.Objects.equals(normalized.activeGitCommitProfileId, empty.activeGitCommitProfileId)
                || !java.util.Objects.equals(normalized.manualCompletionShortcut, empty.manualCompletionShortcut)
                || !java.util.Objects.equals(normalized.profiles, empty.profiles);
        return configured ? 1 : 0;
    }

    private static int parseListSize(String rawJson, java.lang.reflect.Type type) {
        try {
            List<?> list = GSON.fromJson(rawJson, type);
            return list == null ? 0 : list.size();
        } catch (Exception ignored) {
            return 0;
        }
    }
}
