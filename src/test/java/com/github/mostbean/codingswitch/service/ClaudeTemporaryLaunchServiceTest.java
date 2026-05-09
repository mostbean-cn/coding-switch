package com.github.mostbean.codingswitch.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.github.mostbean.codingswitch.model.CliType;
import com.github.mostbean.codingswitch.model.Provider;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import org.junit.Test;

public class ClaudeTemporaryLaunchServiceTest {

    @Test
    public void buildsLaunchRequestWithoutPuttingSecretsInCommand() throws Exception {
        Provider provider = new Provider(CliType.CLAUDE, "Claude API");
        JsonObject config = new JsonObject();
        JsonObject env = new JsonObject();
        env.addProperty("ANTHROPIC_AUTH_TOKEN", "secret-token");
        env.addProperty("ANTHROPIC_BASE_URL", "https://example.com/anthropic");
        env.addProperty("ANTHROPIC_MODEL", "claude-sonnet-4-5");
        config.add("env", env);
        config.addProperty("effortLevel", "high");
        config.addProperty("dangerouslySkipPermissions", true);
        provider.setSettingsConfig(config);

        ClaudeTemporaryLaunchService.LaunchRequest request =
            ClaudeTemporaryLaunchService.buildLaunchRequest(provider);

        try {
            assertCommandUsesInjectedSettings(request);
            org.junit.Assert.assertTrue(request.command().contains("--dangerously-skip-permissions"));
            assertEquals("secret-token", request.environment().get("ANTHROPIC_AUTH_TOKEN"));
            assertEquals("https://example.com/anthropic", request.environment().get("ANTHROPIC_BASE_URL"));
            assertEquals("claude-sonnet-4-5", request.environment().get("ANTHROPIC_MODEL"));
            assertEquals("high", request.environment().get("CLAUDE_CODE_EFFORT_LEVEL"));
            JsonObject settings = readTemporarySettings(request);
            assertEquals("secret-token", settings.getAsJsonObject("env").get("ANTHROPIC_AUTH_TOKEN").getAsString());
            assertEquals("", settings.getAsJsonObject("env").get("ANTHROPIC_API_KEY").getAsString());
            assertFalse(request.command().contains("secret-token"));
            assertFalse(request.command().contains("CODING_SWITCH_CLAUDE_SETTINGS"));
        } finally {
            request.deleteTemporarySettingsFile();
        }
    }

    @Test
    public void clearsBlankEnvironmentValues() throws Exception {
        Provider provider = new Provider(CliType.CLAUDE, "Claude API");
        JsonObject config = new JsonObject();
        JsonObject env = new JsonObject();
        env.addProperty("ANTHROPIC_AUTH_TOKEN", "");
        env.addProperty("ANTHROPIC_MODEL", "claude-sonnet-4-5");
        config.add("env", env);
        provider.setSettingsConfig(config);

        ClaudeTemporaryLaunchService.LaunchRequest request =
            ClaudeTemporaryLaunchService.buildLaunchRequest(provider);

        try {
            assertEquals("", request.environment().get("ANTHROPIC_AUTH_TOKEN"));
            assertEquals("claude-sonnet-4-5", request.environment().get("ANTHROPIC_MODEL"));
            assertCommandUsesInjectedSettings(request);
        } finally {
            request.deleteTemporarySettingsFile();
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNonClaudeProviders() {
        Provider provider = new Provider(CliType.CODEX, "Codex API");

        ClaudeTemporaryLaunchService.buildLaunchRequest(provider);
    }

    private static JsonObject readTemporarySettings(ClaudeTemporaryLaunchService.LaunchRequest request)
        throws Exception {
        return JsonParser.parseString(Files.readString(request.temporarySettingsPath())).getAsJsonObject();
    }

    private static void assertCommandUsesInjectedSettings(ClaudeTemporaryLaunchService.LaunchRequest request) {
        org.junit.Assert.assertTrue(Files.exists(request.temporarySettingsPath()));
        org.junit.Assert.assertTrue(request.command().contains("--settings"));
        org.junit.Assert.assertTrue(request.command().contains(request.temporarySettingsPath().getFileName().toString()));
    }
}
