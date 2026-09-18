package com.github.mostbean.codingswitch.service;

import com.google.gson.JsonObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PiConfigSupportTest {

    @Test
    public void shouldBuildBuiltinAuthWithoutModels() {
        JsonObject config = PiConfigSupport.buildSettingsConfig(
                "deepseek",
                "sk-test",
                "https://api.deepseek.com/v1",
                PiConfigSupport.API_OPENAI_COMPLETIONS,
                "deepseek-v4-pro");
        assertTrue(config.getAsJsonObject("auth").has("deepseek"));
        assertFalse(config.getAsJsonObject("models").has("providers"));
        assertEquals("deepseek", config.getAsJsonObject("settings").get("defaultProvider").getAsString());
        assertEquals("deepseek-v4-pro", config.getAsJsonObject("settings").get("defaultModel").getAsString());
        assertFalse(PiConfigSupport.parse(config).custom());
    }

    @Test
    public void shouldBuildCustomModelsJson() {
        JsonObject config = PiConfigSupport.buildSettingsConfig(
                PiConfigSupport.CUSTOM_PROVIDER_ID,
                "sk-custom",
                "https://api.example.com/v1",
                "openai-completions",
                "my-model");
        PiConfigSupport.ParsedConfig parsed = PiConfigSupport.parse(config);
        assertTrue(parsed.custom());
        assertEquals("coding-switch", parsed.providerId());
        assertEquals("https://api.example.com/v1", parsed.baseUrl());
        assertEquals("my-model", parsed.model());
        assertTrue(config.getAsJsonObject("models").getAsJsonObject("providers").has("coding-switch"));
    }

    @Test
    public void shouldTreatOfficialConfigAsEmptyCustom() {
        JsonObject config = PiConfigSupport.buildOfficialConfig();
        assertFalse(PiConfigSupport.hasCustomModelConfig(config));
        assertTrue(config.getAsJsonObject("auth").keySet().isEmpty());
    }

    @Test
    public void shouldNormalizeApiAliases() {
        assertEquals(PiConfigSupport.API_OPENAI_COMPLETIONS, PiConfigSupport.normalizeApi("chat"));
        assertEquals(PiConfigSupport.API_OPENAI_RESPONSES, PiConfigSupport.normalizeApi("responses"));
        assertEquals(PiConfigSupport.API_ANTHROPIC_MESSAGES, PiConfigSupport.normalizeApi("anthropic"));
    }
}
