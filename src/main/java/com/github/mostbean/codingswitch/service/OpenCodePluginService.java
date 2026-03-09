package com.github.mostbean.codingswitch.service;

import com.github.mostbean.codingswitch.model.CliType;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * OpenCode 插件框架管理服务。
 * 负责在 opencode.json 的 plugin 数组中管理 oh-my-opencode / oh-my-opencode-slim 条目，
 * 并生成对应的默认配置文件。
 */
@Service(Service.Level.APP)
public final class OpenCodePluginService {

    private static final Logger LOG = Logger.getInstance(OpenCodePluginService.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public enum OpenCodePlugin {
        NONE(null, null),
        OH_MY_OPENCODE("oh-my-opencode", "oh-my-opencode.json"),
        OH_MY_OPENCODE_SLIM("oh-my-opencode-slim", "oh-my-opencode-slim.json");

        private final String pluginName;
        private final String configFileName;

        OpenCodePlugin(String pluginName, String configFileName) {
            this.pluginName = pluginName;
            this.configFileName = configFileName;
        }

        public String getPluginName() {
            return pluginName;
        }

        public String getConfigFileName() {
            return configFileName;
        }
    }

    public static OpenCodePluginService getInstance() {
        return ApplicationManager.getApplication().getService(OpenCodePluginService.class);
    }

    /**
     * 检测当前 opencode.json 中激活的插件框架。
     */
    public OpenCodePlugin getActivePlugin() {
        try {
            ConfigFileService svc = ConfigFileService.getInstance();
            Path configPath = svc.getProviderConfigPath(CliType.OPENCODE);
            JsonObject config = svc.readJsonFile(configPath);

            if (!config.has("plugin")) {
                return OpenCodePlugin.NONE;
            }

            JsonElement pluginElement = config.get("plugin");
            if (pluginElement.isJsonArray()) {
                JsonArray plugins = pluginElement.getAsJsonArray();
                for (JsonElement element : plugins) {
                    if (!element.isJsonPrimitive()) {
                        continue;
                    }
                    String name = element.getAsString().toLowerCase(Locale.ROOT);
                    String baseName = name.contains("@") ? name.substring(0, name.indexOf('@')) : name;
                    if ("oh-my-opencode-slim".equals(baseName)) {
                        return OpenCodePlugin.OH_MY_OPENCODE_SLIM;
                    }
                    if ("oh-my-opencode".equals(baseName)) {
                        return OpenCodePlugin.OH_MY_OPENCODE;
                    }
                }
            } else if (pluginElement.isJsonPrimitive()) {
                String name = pluginElement.getAsString().toLowerCase(Locale.ROOT);
                String baseName = name.contains("@") ? name.substring(0, name.indexOf('@')) : name;
                if ("oh-my-opencode-slim".equals(baseName)) {
                    return OpenCodePlugin.OH_MY_OPENCODE_SLIM;
                }
                if ("oh-my-opencode".equals(baseName)) {
                    return OpenCodePlugin.OH_MY_OPENCODE;
                }
            }
        } catch (Exception e) {
            LOG.warn("Failed to detect active OpenCode plugin", e);
        }
        return OpenCodePlugin.NONE;
    }

    /**
     * 激活指定的插件框架：修改 opencode.json 的 plugin 数组。
     */
    public void activatePlugin(OpenCodePlugin plugin) throws IOException {
        ConfigFileService svc = ConfigFileService.getInstance();
        Path configPath = svc.getProviderConfigPath(CliType.OPENCODE);
        JsonObject config = svc.readJsonFile(configPath);

        JsonArray plugins = config.has("plugin") && config.get("plugin").isJsonArray()
                ? config.getAsJsonArray("plugin")
                : new JsonArray();

        JsonArray cleaned = removePluginEntries(plugins);

        if (plugin != OpenCodePlugin.NONE && plugin.getPluginName() != null) {
            cleaned.add(plugin.getPluginName());
        }

        config.add("plugin", cleaned);
        svc.writeJsonFile(configPath, config);
    }

    /**
     * 停用所有插件框架（从 plugin 数组中移除 oh-my-opencode 相关条目）。
     */
    public void deactivatePlugin() throws IOException {
        activatePlugin(OpenCodePlugin.NONE);
    }

    /**
     * 检查插件的配置文件是否已存在。
     */
    public boolean pluginConfigExists(OpenCodePlugin plugin) {
        if (plugin == OpenCodePlugin.NONE || plugin.getConfigFileName() == null) {
            return false;
        }
        Path configDir = ConfigFileService.getInstance().getConfigDir(CliType.OPENCODE);
        return Files.exists(configDir.resolve(plugin.getConfigFileName()));
    }

    /**
     * 为指定的插件生成默认配置文件。
     */
    public void generateDefaultConfig(OpenCodePlugin plugin) throws IOException {
        if (plugin == OpenCodePlugin.NONE || plugin.getConfigFileName() == null) {
            return;
        }
        ConfigFileService svc = ConfigFileService.getInstance();
        Path configDir = svc.getConfigDir(CliType.OPENCODE);
        Path configPath = configDir.resolve(plugin.getConfigFileName());

        JsonObject defaultConfig = switch (plugin) {
            case OH_MY_OPENCODE -> buildOhMyOpenCodeDefault();
            case OH_MY_OPENCODE_SLIM -> buildOhMyOpenCodeSlimDefault();
            default -> new JsonObject();
        };

        svc.writeJsonFile(configPath, defaultConfig);
    }

    private static JsonArray removePluginEntries(JsonArray plugins) {
        JsonArray result = new JsonArray();
        for (JsonElement element : plugins) {
            if (!element.isJsonPrimitive()) {
                result.add(element);
                continue;
            }
            String name = element.getAsString().toLowerCase(Locale.ROOT);
            String baseName = name.contains("@") ? name.substring(0, name.indexOf('@')) : name;
            if ("oh-my-opencode".equals(baseName) || "oh-my-opencode-slim".equals(baseName)) {
                continue;
            }
            result.add(element);
        }
        return result;
    }

    private static JsonObject buildOhMyOpenCodeDefault() {
        JsonObject config = new JsonObject();

        JsonObject agents = new JsonObject();
        JsonObject plannerSisyphus = new JsonObject();
        plannerSisyphus.addProperty("enabled", true);
        plannerSisyphus.addProperty("replace_plan", true);
        agents.add("planner-sisyphus", plannerSisyphus);

        JsonObject librarian = new JsonObject();
        librarian.addProperty("enabled", true);
        agents.add("librarian", librarian);

        JsonObject explore = new JsonObject();
        explore.addProperty("enabled", true);
        agents.add("explore", explore);

        JsonObject oracle = new JsonObject();
        oracle.addProperty("enabled", true);
        agents.add("oracle", oracle);

        config.add("agents", agents);
        config.add("disabled_hooks", new JsonArray());
        config.add("disabled_mcps", new JsonArray());

        return config;
    }

    private static JsonObject buildOhMyOpenCodeSlimDefault() {
        JsonObject config = new JsonObject();
        config.addProperty("preset", "default");

        JsonObject presets = new JsonObject();
        JsonObject defaultPreset = new JsonObject();

        String[] agentNames = { "orchestrator", "explorer", "oracle", "designer", "fixer", "librarian" };
        for (String agentName : agentNames) {
            JsonObject agent = new JsonObject();
            agent.addProperty("model", "");
            JsonArray skills = new JsonArray();
            skills.add("*");
            agent.add("skills", skills);
            JsonArray mcps = new JsonArray();
            mcps.add("*");
            agent.add("mcps", mcps);
            defaultPreset.add(agentName, agent);
        }

        presets.add("default", defaultPreset);
        config.add("presets", presets);

        return config;
    }
}
