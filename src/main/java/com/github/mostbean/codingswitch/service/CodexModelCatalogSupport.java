package com.github.mostbean.codingswitch.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Codex {@code model_catalog_json} 的结构转换与路径处理工具。
 */
public final class CodexModelCatalogSupport {

    public static final String SETTINGS_KEY = "modelCatalog";
    private static final String BASE_INSTRUCTIONS =
            "You are Codex, a coding agent. You and the user share the same workspace "
                    + "and collaborate to achieve the user's goals.";

    private CodexModelCatalogSupport() {
    }

    public record ModelDefinition(String displayName, String model, long contextWindow) {
    }

    public static JsonObject buildCatalog(List<ModelDefinition> definitions) {
        JsonArray models = new JsonArray();
        int priority = 0;
        for (ModelDefinition definition : definitions) {
            if (definition == null
                    || definition.displayName() == null || definition.displayName().isBlank()
                    || definition.model() == null || definition.model().isBlank()
                    || definition.contextWindow() <= 0) {
                continue;
            }
            models.add(buildModel(definition, priority++));
        }

        JsonObject catalog = new JsonObject();
        catalog.add("models", models);
        return catalog;
    }

    public static List<ModelDefinition> readDefinitions(JsonObject catalog) {
        List<ModelDefinition> definitions = new ArrayList<>();
        if (catalog == null || !catalog.has("models") || !catalog.get("models").isJsonArray()) {
            return definitions;
        }

        for (JsonElement element : catalog.getAsJsonArray("models")) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject model = element.getAsJsonObject();
            String displayName = stringValue(model, "display_name");
            String slug = stringValue(model, "slug");
            long contextWindow = longValue(model, "context_window");
            if (!displayName.isBlank() && !slug.isBlank() && contextWindow > 0) {
                definitions.add(new ModelDefinition(displayName, slug, contextWindow));
            }
        }
        return definitions;
    }

    public static String ensureCatalogPath(String toml, Path catalogPath) {
        String safeToml = toml == null ? "" : toml;
        String normalizedPath = catalogPath.toAbsolutePath().normalize().toString().replace('\\', '/');
        String catalogLine = "model_catalog_json = \"" + normalizedPath.replace("\"", "\\\"") + "\"";

        StringBuilder result = new StringBuilder();
        boolean replaced = false;
        for (String rawLine : safeToml.split("\n", -1)) {
            String trimmed = rawLine.trim();
            if (!replaced && trimmed.startsWith("model_catalog_json") && trimmed.contains("=")) {
                result.append(catalogLine).append('\n');
                replaced = true;
            } else {
                result.append(rawLine).append('\n');
            }
        }
        if (!replaced) {
            result.insert(0, catalogLine + "\n");
        }
        return result.toString().stripTrailing() + "\n";
    }

    private static JsonObject buildModel(ModelDefinition definition, int priority) {
        JsonObject model = new JsonObject();
        model.addProperty("slug", definition.model().trim());
        model.addProperty("display_name", definition.displayName().trim());
        model.add("description", null);
        model.addProperty("default_reasoning_level", "high");
        model.add("supported_reasoning_levels", reasoningLevels());
        model.addProperty("shell_type", "default");
        model.addProperty("visibility", "list");
        model.addProperty("supported_in_api", true);
        model.addProperty("priority", priority);
        model.add("availability_nux", null);
        model.add("upgrade", null);
        model.addProperty("base_instructions", BASE_INSTRUCTIONS);
        model.addProperty("supports_reasoning_summaries", false);
        model.addProperty("support_verbosity", false);
        model.add("default_verbosity", null);
        model.add("apply_patch_tool_type", null);

        JsonObject truncationPolicy = new JsonObject();
        truncationPolicy.addProperty("mode", "bytes");
        truncationPolicy.addProperty("limit", 10_000);
        model.add("truncation_policy", truncationPolicy);

        model.addProperty("supports_parallel_tool_calls", false);
        model.addProperty("context_window", definition.contextWindow());
        model.addProperty("max_context_window", definition.contextWindow());
        model.add("experimental_supported_tools", new JsonArray());
        return model;
    }

    private static JsonArray reasoningLevels() {
        JsonArray levels = new JsonArray();
        levels.add(reasoningLevel("low", "Fast responses with lighter reasoning"));
        levels.add(reasoningLevel("medium", "Balances speed and reasoning depth"));
        levels.add(reasoningLevel("high", "Deeper reasoning for complex tasks"));
        levels.add(reasoningLevel("xhigh", "Maximum reasoning depth"));
        return levels;
    }

    private static JsonObject reasoningLevel(String effort, String description) {
        JsonObject level = new JsonObject();
        level.addProperty("effort", effort);
        level.addProperty("description", description);
        return level;
    }

    private static String stringValue(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "";
    }

    private static long longValue(JsonObject object, String key) {
        try {
            return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsLong() : 0L;
        } catch (RuntimeException ignored) {
            return 0L;
        }
    }
}
