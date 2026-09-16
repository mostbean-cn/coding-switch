package com.github.mostbean.codingswitch.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Grok Build 官方 config.toml 自定义模型段的生成与解析。
 * 字段对齐 Grok 文档中的 [models] / [model.&lt;name&gt;]。
 */
public final class GrokConfigSupport {

    public static final String DEFAULT_BACKEND = "chat_completions";
    public static final List<String> API_BACKENDS = List.of("chat_completions", "responses", "messages");

    public record ModelDefinition(
            String key,
            String name,
            String model,
            String baseUrl,
            String apiKey,
            String apiBackend,
            Long contextWindow
    ) {
        public ModelDefinition {
            key = sanitizeKey(key);
            name = name == null ? "" : name.trim();
            model = model == null ? "" : model.trim();
            baseUrl = normalizeBaseUrl(baseUrl);
            apiKey = apiKey == null ? "" : apiKey.trim();
            apiBackend = normalizeBackend(apiBackend);
        }
    }

    public record ParsedConfig(
            String defaultModelKey,
            String apiKey,
            String baseUrl,
            String apiBackend,
            List<ModelDefinition> models
    ) {
        public ParsedConfig {
            models = models == null ? List.of() : List.copyOf(models);
        }
    }

    private GrokConfigSupport() {
    }

    public static String buildToml(String apiKey, String baseUrl, String apiBackend, List<ModelDefinition> models) {
        List<ModelDefinition> safeModels = models == null ? List.of() : models.stream()
                .filter(model -> model != null && !model.model().isBlank())
                .toList();
        if (safeModels.isEmpty()) {
            return "";
        }

        String sharedKey = apiKey == null ? "" : apiKey.trim();
        String sharedBaseUrl = normalizeBaseUrl(baseUrl);
        String sharedBackend = normalizeBackend(apiBackend);
        Set<String> usedKeys = new LinkedHashSet<>();
        List<ModelDefinition> uniqueModels = new ArrayList<>();
        for (ModelDefinition model : safeModels) {
            String key = uniqueKey(model.key(), model.name(), model.model(), usedKeys);
            usedKeys.add(key);
            uniqueModels.add(new ModelDefinition(
                    key,
                    model.name().isBlank() ? model.model() : model.name(),
                    model.model(),
                    firstNotBlank(model.baseUrl(), sharedBaseUrl),
                    firstNotBlank(model.apiKey(), sharedKey),
                    firstNotBlank(model.apiBackend(), sharedBackend),
                    model.contextWindow()
            ));
        }

        StringBuilder toml = new StringBuilder();
        toml.append("[models]\n");
        toml.append("default = \"").append(escapeToml(uniqueModels.get(0).key())).append("\"\n\n");
        for (ModelDefinition model : uniqueModels) {
            toml.append("[model.").append(quoteKeyIfNeeded(model.key())).append("]\n");
            toml.append("model = \"").append(escapeToml(model.model())).append("\"\n");
            if (!model.name().isBlank()) {
                toml.append("name = \"").append(escapeToml(model.name())).append("\"\n");
            }
            if (!model.baseUrl().isBlank()) {
                toml.append("base_url = \"").append(escapeToml(model.baseUrl())).append("\"\n");
            }
            if (!model.apiKey().isBlank()) {
                toml.append("api_key = \"").append(escapeToml(model.apiKey())).append("\"\n");
            }
            toml.append("api_backend = \"").append(escapeToml(model.apiBackend())).append("\"\n");
            if (model.contextWindow() != null && model.contextWindow() > 0) {
                toml.append("context_window = ").append(model.contextWindow()).append("\n");
            }
            if ("messages".equals(model.apiBackend())) {
                toml.append("extra_headers = { \"anthropic-version\" = \"2023-06-01\" }\n");
            }
            toml.append("\n");
        }
        return toml.toString().stripTrailing() + "\n";
    }

    public static ParsedConfig parse(String toml) {
        String safe = toml == null ? "" : toml;
        String defaultKey = null;
        String currentSection = null;
        Map<String, Map<String, String>> sections = new LinkedHashMap<>();
        Map<String, String> root = new LinkedHashMap<>();

        for (String rawLine : safe.split("\n", -1)) {
            String line = stripComment(rawLine).trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("[") && line.endsWith("]")) {
                currentSection = unquoteSection(line.substring(1, line.length() - 1).trim());
                sections.computeIfAbsent(currentSection, ignored -> new LinkedHashMap<>());
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = line.substring(0, eq).trim();
            String value = unquote(line.substring(eq + 1).trim());
            if (currentSection == null) {
                root.put(key, value);
            } else {
                sections.computeIfAbsent(currentSection, ignored -> new LinkedHashMap<>()).put(key, value);
            }
        }

        Map<String, String> modelsTable = sections.getOrDefault("models", Map.of());
        defaultKey = firstNotBlank(modelsTable.get("default"), root.get("default"));

        List<ModelDefinition> models = new ArrayList<>();
        for (Map.Entry<String, Map<String, String>> entry : sections.entrySet()) {
            String section = entry.getKey();
            if (!section.startsWith("model.") || section.equals("models")) {
                continue;
            }
            String key = section.substring("model.".length()).trim();
            Map<String, String> values = entry.getValue();
            String modelId = firstNotBlank(values.get("model"), key);
            String contextRaw = values.get("context_window");
            Long contextWindow = null;
            if (contextRaw != null && contextRaw.matches("\\d+")) {
                contextWindow = Long.parseLong(contextRaw);
            }
            models.add(new ModelDefinition(
                    key,
                    firstNotBlank(values.get("name"), modelId),
                    modelId,
                    values.get("base_url"),
                    values.get("api_key"),
                    values.get("api_backend"),
                    contextWindow
            ));
        }

        String apiKey = models.stream().map(ModelDefinition::apiKey).filter(v -> v != null && !v.isBlank()).findFirst().orElse("");
        String baseUrl = models.stream().map(ModelDefinition::baseUrl).filter(v -> v != null && !v.isBlank()).findFirst().orElse("");
        String backend = models.stream().map(ModelDefinition::apiBackend).filter(v -> v != null && !v.isBlank()).findFirst().orElse(DEFAULT_BACKEND);
        if (defaultKey != null && !defaultKey.isBlank()) {
            List<ModelDefinition> reordered = new ArrayList<>();
            for (ModelDefinition model : models) {
                if (defaultKey.equals(model.key())) {
                    reordered.add(model);
                }
            }
            for (ModelDefinition model : models) {
                if (!defaultKey.equals(model.key())) {
                    reordered.add(model);
                }
            }
            models = reordered;
        }
        return new ParsedConfig(defaultKey, apiKey, baseUrl, backend, models);
    }

    public static boolean hasCustomModelConfig(String toml) {
        if (toml == null || toml.isBlank()) {
            return false;
        }
        return toml.contains("[model.") || toml.contains("base_url =") || toml.contains("api_backend =") || toml.contains("api_key =");
    }

    public static Set<String> extractManagedModelKeys(String toml) {
        ParsedConfig parsed = parse(toml);
        Set<String> keys = new LinkedHashSet<>();
        for (ModelDefinition model : parsed.models()) {
            keys.add(model.key());
        }
        return keys;
    }

    public static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "";
        }
        String trimmed = baseUrl.trim().replaceAll("/+$", "");
        if (trimmed.matches("https?://[^/]+")) {
            return trimmed + "/v1";
        }
        return trimmed;
    }

    public static String normalizeBackend(String apiBackend) {
        if (apiBackend == null || apiBackend.isBlank()) {
            return DEFAULT_BACKEND;
        }
        String normalized = apiBackend.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        if ("chat".equals(normalized) || "chatcompletions".equals(normalized) || "chat_completion".equals(normalized)) {
            return "chat_completions";
        }
        if ("response".equals(normalized)) {
            return "responses";
        }
        if ("message".equals(normalized) || "anthropic".equals(normalized) || "anthropic_messages".equals(normalized)) {
            return "messages";
        }
        return API_BACKENDS.contains(normalized) ? normalized : DEFAULT_BACKEND;
    }

    public static String sanitizeKey(String raw) {
        if (raw == null || raw.isBlank()) {
            return "custom";
        }
        StringBuilder out = new StringBuilder();
        boolean previousDash = false;
        for (char ch : raw.trim().toLowerCase(Locale.ROOT).toCharArray()) {
            if (Character.isLetterOrDigit(ch) || ch == '_' || ch == '-') {
                out.append(ch);
                previousDash = false;
            } else if (!previousDash && out.length() > 0) {
                out.append('-');
                previousDash = true;
            }
        }
        String sanitized = out.toString().replaceAll("^-+|-+$", "");
        return sanitized.isBlank() ? "custom" : sanitized;
    }

    public static String escapeToml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public static String firstNotBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a.trim();
        }
        if (b != null && !b.isBlank()) {
            return b.trim();
        }
        return "";
    }

    private static String uniqueKey(String preferred, String name, String model, Set<String> used) {
        String base = sanitizeKey(firstNotBlank(preferred, firstNotBlank(name, model)));
        String candidate = base;
        int index = 2;
        while (used.contains(candidate)) {
            candidate = base + "-" + index;
            index++;
        }
        return candidate;
    }

    private static String quoteKeyIfNeeded(String key) {
        if (key.matches("[A-Za-z0-9_-]+")) {
            return key;
        }
        return "\"" + escapeToml(key) + "\"";
    }

    private static String stripComment(String line) {
        boolean inQuotes = false;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"' && (i == 0 || line.charAt(i - 1) != '\\')) {
                inQuotes = !inQuotes;
            }
            if (!inQuotes && ch == '#') {
                break;
            }
            out.append(ch);
        }
        return out.toString();
    }

    private static String unquote(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
        }
        if (trimmed.length() >= 2 && trimmed.startsWith("'") && trimmed.endsWith("'")) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static String unquoteSection(String section) {
        return section.replace("\"", "").replace("'", "");
    }
}
