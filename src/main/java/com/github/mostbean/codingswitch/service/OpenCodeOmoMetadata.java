package com.github.mostbean.codingswitch.service;

import com.github.mostbean.codingswitch.model.Provider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Centralized OMO/OMO Slim metadata used by the OpenCode editor UI.
 */
public final class OpenCodeOmoMetadata {

    public enum AgentGroup {
        MAIN,
        SUB
    }

    public record OmoAgentDefinition(String key, String displayName, AgentGroup group, String recommendedModel) {
    }

    public record OmoCategoryDefinition(String key, String displayName, String recommendedModel) {
    }

    private static final List<OmoAgentDefinition> STANDARD_AGENTS = List.of(
            new OmoAgentDefinition("sisyphus", "Sisyphus", AgentGroup.MAIN, "claude-opus-4-6"),
            new OmoAgentDefinition("hephaestus", "Hephaestus", AgentGroup.MAIN, "gpt-5.3-codex"),
            new OmoAgentDefinition("prometheus", "Prometheus", AgentGroup.MAIN, "claude-opus-4-6"),
            new OmoAgentDefinition("atlas", "Atlas", AgentGroup.MAIN, "kimi-k2.5"),
            new OmoAgentDefinition("oracle", "Oracle", AgentGroup.SUB, "gpt-5.2"),
            new OmoAgentDefinition("librarian", "Librarian", AgentGroup.SUB, "gemini-3-flash"),
            new OmoAgentDefinition("explore", "Explore", AgentGroup.SUB, "grok-code-fast-1"),
            new OmoAgentDefinition("multimodal-looker", "Multimodal-Looker", AgentGroup.SUB, "kimi-k2.5"),
            new OmoAgentDefinition("metis", "Metis", AgentGroup.SUB, "claude-opus-4-6"),
            new OmoAgentDefinition("momus", "Momus", AgentGroup.SUB, "gpt-5.2"),
            new OmoAgentDefinition("sisyphus-junior", "Sisyphus-Junior", AgentGroup.SUB, null));

    private static final List<OmoAgentDefinition> SLIM_AGENTS = List.of(
            new OmoAgentDefinition("orchestrator", "Orchestrator", AgentGroup.MAIN, "claude-opus-4-6"),
            new OmoAgentDefinition("oracle", "Oracle", AgentGroup.SUB, "gpt-5.2"),
            new OmoAgentDefinition("librarian", "Librarian", AgentGroup.SUB, "gemini-3-flash"),
            new OmoAgentDefinition("explorer", "Explorer", AgentGroup.SUB, "grok-code-fast-1"),
            new OmoAgentDefinition("designer", "Designer", AgentGroup.SUB, "gemini-3-pro"),
            new OmoAgentDefinition("fixer", "Fixer", AgentGroup.SUB, "gpt-5.3-codex"));

    private static final List<OmoCategoryDefinition> STANDARD_CATEGORIES = List.of(
            new OmoCategoryDefinition("visual-engineering", "Visual Engineering", "gemini-3-pro"),
            new OmoCategoryDefinition("ultrabrain", "Ultrabrain", "gpt-5.3-codex"),
            new OmoCategoryDefinition("deep", "Deep", "gpt-5.3-codex"),
            new OmoCategoryDefinition("artistry", "Artistry", "gemini-3-pro"),
            new OmoCategoryDefinition("quick", "Quick", "claude-haiku-4-5"),
            new OmoCategoryDefinition("unspecified-low", "Unspecified Low", "claude-sonnet-4-6"),
            new OmoCategoryDefinition("unspecified-high", "Unspecified High", "claude-opus-4-6"),
            new OmoCategoryDefinition("writing", "Writing", "gemini-3-flash"));

    private static final Map<String, Map<String, List<String>>> MODEL_VARIANT_FALLBACKS = createVariantFallbacks();

    private OpenCodeOmoMetadata() {
    }

    public static List<OmoAgentDefinition> getBuiltinAgents(String category) {
        return Provider.CATEGORY_OMO_SLIM.equals(category) ? SLIM_AGENTS : STANDARD_AGENTS;
    }

    public static List<OmoAgentDefinition> getBuiltinMainAgents(String category) {
        return getBuiltinAgents(category).stream()
                .filter(agent -> agent.group() == AgentGroup.MAIN)
                .toList();
    }

    public static List<OmoAgentDefinition> getBuiltinSubAgents(String category) {
        return getBuiltinAgents(category).stream()
                .filter(agent -> agent.group() == AgentGroup.SUB)
                .toList();
    }

    public static List<OmoCategoryDefinition> getBuiltinCategories(String category) {
        return Provider.CATEGORY_OMO_SLIM.equals(category) ? List.of() : STANDARD_CATEGORIES;
    }

    public static List<String> getVariantFallback(String npmPackage, String modelId) {
        if (npmPackage == null || npmPackage.isBlank() || modelId == null || modelId.isBlank()) {
            return List.of();
        }
        Map<String, List<String>> byModel = MODEL_VARIANT_FALLBACKS.get(npmPackage.trim());
        if (byModel == null) {
            return List.of();
        }
        List<String> variants = byModel.get(modelId.trim());
        return variants != null ? variants : List.of();
    }

    private static Map<String, Map<String, List<String>>> createVariantFallbacks() {
        Map<String, Map<String, List<String>>> result = new LinkedHashMap<>();
        result.put("@ai-sdk/google", createGoogleVariants());
        result.put("@ai-sdk/openai", createOpenAiVariants());
        result.put("@ai-sdk/anthropic", createAnthropicVariants());
        return result;
    }

    private static Map<String, List<String>> createGoogleVariants() {
        Map<String, List<String>> variants = new LinkedHashMap<>();
        variants.put("gemini-2.5-flash-lite", List.of("auto", "no-thinking"));
        variants.put("gemini-3-flash-preview", List.of("minimal", "low", "medium", "high"));
        variants.put("gemini-3-pro-preview", List.of("low", "high"));
        return variants;
    }

    private static Map<String, List<String>> createOpenAiVariants() {
        Map<String, List<String>> variants = new LinkedHashMap<>();
        List<String> lowMediumHigh = List.of("low", "medium", "high");
        variants.put("gpt-5", lowMediumHigh);
        variants.put("gpt-5.1", lowMediumHigh);
        variants.put("gpt-5.1-codex", lowMediumHigh);
        variants.put("gpt-5.2", List.of("low", "medium", "high", "xhigh"));
        variants.put("gpt-5.2-codex", List.of("low", "medium", "high", "xhigh"));
        variants.put("gpt-5.3-codex", List.of("low", "medium", "high", "xhigh"));
        variants.put("gpt-5.1-codex-max", List.of("low", "medium", "high", "xhigh"));
        return variants;
    }

    private static Map<String, List<String>> createAnthropicVariants() {
        Map<String, List<String>> variants = new LinkedHashMap<>();
        variants.put("claude-sonnet-4-5-20250929", List.of("low", "medium", "high"));
        variants.put("claude-opus-4-5-20251101", List.of("low", "medium", "high"));
        variants.put("claude-opus-4-6", List.of("low", "medium", "high", "max"));
        variants.put("gemini-claude-opus-4-5-thinking", List.of("low", "medium", "high"));
        variants.put("gemini-claude-sonnet-4-5-thinking", List.of("low", "medium", "high"));
        return variants;
    }

    public static String resolveRecommendedModelValue(String recommendedModel, List<String> availableValues) {
        if (recommendedModel == null || recommendedModel.isBlank() || availableValues == null || availableValues.isEmpty()) {
            return null;
        }

        for (String available : availableValues) {
            if (recommendedModel.equals(available)) {
                return available;
            }
        }
        for (String available : availableValues) {
            if (available != null && available.endsWith("/" + recommendedModel)) {
                return available;
            }
        }
        return null;
    }

    public static List<String> collectRecommendedModels(String category) {
        List<String> models = new ArrayList<>();
        for (OmoAgentDefinition agent : getBuiltinAgents(category)) {
            if (agent.recommendedModel() != null && !agent.recommendedModel().isBlank()) {
                models.add(agent.recommendedModel());
            }
        }
        for (OmoCategoryDefinition definition : getBuiltinCategories(category)) {
            if (definition.recommendedModel() != null && !definition.recommendedModel().isBlank()) {
                models.add(definition.recommendedModel());
            }
        }
        return models;
    }
}
