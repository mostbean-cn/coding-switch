package com.github.mostbean.codingswitch.service;

import com.github.mostbean.codingswitch.model.CliType;
import com.github.mostbean.codingswitch.model.Provider;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 预制 Provider 模板（国产供应商 + 常用模型）。
 * 用户只需选择模板并填写 API Key 即可一键创建配置。
 */
public final class ProviderPresets {

        private ProviderPresets() {
        }

        // =====================================================================
        // 预设数据
        // =====================================================================

        public record Preset(String name, CliType cliType, JsonObject settingsConfig) {
        }

        /**
         * 获取所有预设模板。
         */
        public static List<Preset> all() {
                List<Preset> presets = new ArrayList<>();

                // ===== Official 预设（恢复 CLI 官方认证） =====
                presets.add(claudeOfficial());
                presets.add(codexOfficial());
                presets.add(geminiOfficial());

                // ===== Claude Code 国产供应商预设 =====
                presets.add(claudePreset("DeepSeek",
                                "https://api.deepseek.com/anthropic", "DeepSeek-V3.2"));
                presets.add(claudePreset("智谱 GLM",
                                "https://open.bigmodel.cn/api/anthropic", "GLM-5"));
                presets.add(claudePreset("MiniMax",
                                "https://api.minimaxi.com/anthropic", "MiniMax-M2.5"));
                presets.add(claudePreset("Kimi",
                                "https://api.kimi.com/coding/", "kimi-for-coding"));
                presets.add(claudePreset("百度千帆",
                                "https://qianfan.baidubce.com/anthropic/coding", "qianfan-code-latest"));
                presets.add(claudePreset("阿里 Plan",
                                "https://coding.dashscope.aliyuncs.com/apps/anthropic", "qwen3.5-plus"));

                // ===== Codex 国产供应商预设 =====
                presets.add(codexPreset("DeepSeek",
                                "https://api.deepseek.com/v1", "DeepSeek-V3.2"));
                presets.add(codexPreset("智谱 GLM",
                                "https://open.bigmodel.cn/api/paas/v4", "GLM-5"));
                presets.add(codexPreset("Kimi",
                                "https://api.moonshot.cn/v1", "kimi-for-coding"));

                // ===== OpenCode 国产供应商预设 =====
                presets.add(opencodePreset("DeepSeek",
                                "https://api.deepseek.com/v1", "DeepSeek-V3.2"));
                presets.add(opencodePreset("智谱 GLM",
                                "https://open.bigmodel.cn/api/paas/v4", "GLM-5"));
                presets.add(opencodePreset("MiniMax",
                                "https://api.minimaxi.com/v1", "MiniMax-M2.5"));
                presets.add(opencodePreset("Kimi",
                                "https://api.moonshot.cn/v1", "kimi-for-coding"));

                return presets;
        }

        /**
         * 按 CLI 类型筛选预设。
         */
        public static List<Preset> forCli(CliType cliType) {
                return all().stream().filter(p -> p.cliType == cliType).toList();
        }

        /**
         * 将预设转为 Provider 对象（API Key 留空，需要用户填写）。
         */
        public static Provider toProvider(Preset preset) {
                Provider p = new Provider(preset.cliType, preset.name);
                p.setSettingsConfig(preset.settingsConfig.deepCopy());
                return p;
        }

        // =====================================================================
        // 内部构建方法
        // =====================================================================

        private static Preset claudePreset(String name, String baseUrl, String model) {
                JsonObject config = new JsonObject();
                JsonObject env = new JsonObject();
                env.addProperty("ANTHROPIC_BASE_URL", baseUrl);
                env.addProperty("ANTHROPIC_AUTH_TOKEN", ""); // 占位，用户需要填写
                env.addProperty("ANTHROPIC_MODEL", model);
                config.add("env", env);
                return new Preset(name, CliType.CLAUDE, config);
        }

        private static Preset codexPreset(String name, String baseUrl, String model) {
                JsonObject config = new JsonObject();
                JsonObject auth = new JsonObject();
                auth.addProperty("OPENAI_API_KEY", ""); // 占位，用户需要填写
                config.add("auth", auth);

                StringBuilder toml = new StringBuilder();
                toml.append("model_provider = \"custom\"\n");
                toml.append("model = \"").append(model).append("\"\n");
                toml.append("model_reasoning_effort = \"high\"\n");
                toml.append("disable_response_storage = true\n\n");
                toml.append("[model_providers.custom]\n");
                toml.append("name = \"custom\"\n");
                toml.append("base_url = \"").append(baseUrl).append("\"\n");
                toml.append("wire_api = \"responses\"\n");
                toml.append("requires_openai_auth = true\n");
                config.addProperty("config", toml.toString());

                return new Preset(name, CliType.CODEX, config);
        }

        private static Preset opencodePreset(String name, String baseUrl, String model) {
                JsonObject config = new JsonObject();
                config.addProperty("npm", "@ai-sdk/openai-compatible");

                JsonObject options = new JsonObject();
                options.addProperty("baseURL", baseUrl);
                options.addProperty("apiKey", ""); // 占位，用户需要填写
                config.add("options", options);

                JsonObject models = new JsonObject();
                JsonObject modelDef = new JsonObject();
                modelDef.addProperty("name", model);
                models.add(model, modelDef);
                config.add("models", models);

                return new Preset(name, CliType.OPENCODE, config);
        }

        // ===== Official（恢复官方认证）预设 =====

        private static Preset claudeOfficial() {
                JsonObject config = new JsonObject();
                config.add("env", new JsonObject()); // 空 env → 清除第三方变量，回退到 Anthropic OAuth
                return new Preset("Official Login", CliType.CLAUDE, config);
        }

        private static Preset codexOfficial() {
                JsonObject config = new JsonObject();
                config.add("auth", new JsonObject()); // 空 auth → 清除 OPENAI_API_KEY
                config.addProperty("config", ""); // 空 config → 清除 config.toml 内容
                return new Preset("Official Login", CliType.CODEX, config);
        }

        private static Preset geminiOfficial() {
                JsonObject config = new JsonObject();
                config.add("env", new JsonObject()); // 空 env → 清除第三方变量，回退到 Google OAuth
                return new Preset("Official Login", CliType.GEMINI, config);
        }
}
