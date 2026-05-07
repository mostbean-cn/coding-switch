package com.github.mostbean.codingswitch.service;

import com.github.mostbean.codingswitch.model.CliType;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Service(Service.Level.APP)
public final class ProviderConnectionTestService {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(8);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
    private static final Gson GSON = new Gson();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

    public record TestResult(boolean success, int statusCode, String message, long durationMs) {
    }

    private record ProbeRequest(String method, String url, List<Header> headers, String body, String label) {
    }

    private record Header(String key, String value) {
    }

    public static ProviderConnectionTestService getInstance() {
        return ApplicationManager.getApplication().getService(ProviderConnectionTestService.class);
    }

    public TestResult test(CliType cliType, JsonObject settingsConfig) {
        long start = System.currentTimeMillis();
        try {
            List<ProbeRequest> probes = buildProbeRequests(cliType, settingsConfig);
            if (probes.isEmpty()) {
                return new TestResult(false, -1, "No probe endpoint available", cost(start));
            }

            TestResult lastFail = null;
            for (ProbeRequest probe : probes) {
                TestResult result = doProbe(probe);
                if (result.success()) {
                    return new TestResult(true, result.statusCode(), result.message(), cost(start));
                }
                lastFail = chooseBetterFailure(lastFail, result);
            }
            if (lastFail != null) {
                return new TestResult(false, lastFail.statusCode(), lastFail.message(), cost(start));
            }
            return new TestResult(false, -1, "Unknown probe failure", cost(start));
        } catch (IllegalArgumentException e) {
            return new TestResult(false, -1, e.getMessage(), cost(start));
        } catch (Exception e) {
            return new TestResult(false, -1, e.getMessage(), cost(start));
        }
    }

    private TestResult doProbe(ProbeRequest probe) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(probe.url()))
                    .timeout(REQUEST_TIMEOUT);
            for (Header header : probe.headers()) {
                if (header.value() != null && !header.value().isBlank()) {
                    builder.header(header.key(), header.value());
                }
            }

            if ("POST".equalsIgnoreCase(probe.method())) {
                builder.POST(HttpRequest.BodyPublishers.ofString(
                        probe.body() == null ? "" : probe.body(),
                        StandardCharsets.UTF_8));
            } else {
                builder.GET();
            }

            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            int code = response.statusCode();
            if (code >= 200 && code < 300) {
                return new TestResult(true, code, probe.label() + " OK", 0);
            }
            String body = response.body() == null ? "" : response.body();
            body = body.length() > 180 ? body.substring(0, 180) + "..." : body;
            return new TestResult(false, code,
                    probe.label() + " HTTP " + code + (body.isBlank() ? "" : " - " + body),
                    0);
        } catch (IOException e) {
            return new TestResult(false, -1, probe.label() + " I/O error: " + e.getMessage(), 0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new TestResult(false, -1, probe.label() + " Interrupted", 0);
        } catch (Exception e) {
            return new TestResult(false, -1, probe.label() + " " + e.getMessage(), 0);
        }
    }

    private List<ProbeRequest> buildProbeRequests(CliType cliType, JsonObject config) {
        return switch (cliType) {
            case CLAUDE -> buildClaudeProbes(config);
            case CODEX -> buildCodexProbes(config);
            case GEMINI -> buildGeminiProbes(config);
            case OPENCODE -> buildOpenCodeProbes(config);
        };
    }

    private List<ProbeRequest> buildClaudeProbes(JsonObject config) {
        JsonObject env = config != null && config.has("env") && config.get("env").isJsonObject()
                ? config.getAsJsonObject("env")
                : new JsonObject();

        String baseUrl = trimTrailingSlash(getString(env, "ANTHROPIC_BASE_URL"));
        String token = firstNotBlank(getString(env, "ANTHROPIC_AUTH_TOKEN"), getString(env, "ANTHROPIC_API_KEY"));
        if (token == null) {
            throw new IllegalArgumentException("Missing Claude API key/token");
        }
        if (baseUrl == null) {
            baseUrl = "https://api.anthropic.com";
        }
        String model = resolveClaudeProbeModel(env);
        if (model == null) {
            throw new IllegalArgumentException("Missing Claude model");
        }

        List<ProbeRequest> probes = new ArrayList<>();
        String body = claudeMessagesBody(model);
        probes.add(postJson(
                ensurePath(baseUrl, "/v1/messages"),
                List.of(
                        new Header("x-api-key", token),
                        new Header("anthropic-version", "2023-06-01")),
                body,
                "Claude Messages"));
        probes.add(postJson(
                ensurePath(baseUrl, "/messages"),
                List.of(
                        new Header("x-api-key", token),
                        new Header("anthropic-version", "2023-06-01")),
                body,
                "Claude Messages"));
        return probes;
    }

    private List<ProbeRequest> buildCodexProbes(JsonObject config) {
        JsonObject auth = config != null && config.has("auth") && config.get("auth").isJsonObject()
                ? config.getAsJsonObject("auth")
                : new JsonObject();
        String apiKey = getString(auth, "OPENAI_API_KEY");
        if (apiKey == null) {
            throw new IllegalArgumentException("Missing Codex API key");
        }

        String toml = config != null ? getString(config, "config") : null;
        String baseUrl = parseTomlValue(toml, "base_url");
        if (baseUrl == null) {
            baseUrl = "https://api.openai.com/v1";
        }
        String model = parseTomlValue(toml, "model");
        if (model == null) {
            throw new IllegalArgumentException("Missing Codex model");
        }
        String wireApi = firstNotBlank(parseTomlValue(toml, "wire_api"), "responses");

        if ("chat".equalsIgnoreCase(wireApi) || "chat_completions".equalsIgnoreCase(wireApi)
                || "chat-completions".equalsIgnoreCase(wireApi)) {
            return List.of(postJson(
                    ensurePath(baseUrl, "/chat/completions"),
                    bearerHeaders(apiKey),
                    openAiChatBody(model),
                    "Codex Chat Completions"));
        }

        return List.of(postJson(
                ensurePath(baseUrl, "/responses"),
                bearerHeaders(apiKey),
                openAiResponsesBody(model),
                "Codex Responses"));
    }

    private List<ProbeRequest> buildGeminiProbes(JsonObject config) {
        JsonObject env = config != null && config.has("env") && config.get("env").isJsonObject()
                ? config.getAsJsonObject("env")
                : new JsonObject();
        String apiKey = getString(env, "GEMINI_API_KEY");
        if (apiKey == null) {
            throw new IllegalArgumentException("Missing Gemini API key");
        }

        String baseUrl = trimTrailingSlash(getString(env, "GOOGLE_GEMINI_BASE_URL"));
        if (baseUrl == null) {
            baseUrl = "https://generativelanguage.googleapis.com/v1beta";
        }
        String model = getString(env, "GEMINI_MODEL");
        if (model == null) {
            throw new IllegalArgumentException("Missing Gemini model");
        }

        String generateUrl = ensurePath(baseUrl, "/" + normalizeGeminiModelPath(model) + ":generateContent")
                + "?key=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8);
        return List.of(postJson(
                generateUrl,
                List.of(),
                geminiGenerateContentBody(),
                "Gemini generateContent"));
    }

    private List<ProbeRequest> buildOpenCodeProbes(JsonObject config) {
        JsonObject options = config != null && config.has("options") && config.get("options").isJsonObject()
                ? config.getAsJsonObject("options")
                : new JsonObject();
        String apiKey = getString(options, "apiKey");
        if (apiKey == null) {
            throw new IllegalArgumentException("Missing OpenCode API key");
        }

        String baseUrl = trimTrailingSlash(getString(options, "baseURL"));
        if (baseUrl == null) {
            throw new IllegalArgumentException("Missing OpenCode baseURL");
        }

        String npm = getString(config, "npm");
        String model = firstOpenCodeModel(config);
        if (model == null) {
            throw new IllegalArgumentException("Missing OpenCode model");
        }

        if ("@ai-sdk/anthropic".equals(npm)) {
            String body = claudeMessagesBody(model);
            return List.of(
                    postJson(
                            ensurePath(baseUrl, "/v1/messages"),
                            List.of(
                                    new Header("x-api-key", apiKey),
                                    new Header("anthropic-version", "2023-06-01")),
                            body,
                            "OpenCode Anthropic Messages"),
                    postJson(
                            ensurePath(baseUrl, "/messages"),
                            List.of(
                                    new Header("x-api-key", apiKey),
                                    new Header("anthropic-version", "2023-06-01")),
                            body,
                            "OpenCode Anthropic Messages"));
        }
        if ("@ai-sdk/google".equals(npm) || "@ai-sdk/google-vertex".equals(npm)) {
            String generateUrl = ensurePath(baseUrl, "/" + normalizeGeminiModelPath(model) + ":generateContent")
                    + "?key=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8);
            return List.of(postJson(
                    generateUrl,
                    List.of(),
                    geminiGenerateContentBody(),
                    "OpenCode Gemini generateContent"));
        }

        return List.of(postJson(
                ensurePath(baseUrl, "/chat/completions"),
                bearerHeaders(apiKey),
                openAiChatBody(model),
                "OpenCode Chat Completions"));
    }

    private static String getString(JsonObject json, String key) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
            return null;
        }
        String value = json.get(key).getAsString();
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String parseTomlValue(String toml, String key) {
        if (toml == null || toml.isBlank()) {
            return null;
        }
        for (String line : toml.split("\\n")) {
            String trimmed = line.trim();
            int eq = trimmed.indexOf('=');
            if (eq > 0) {
                String parsedKey = trimmed.substring(0, eq).trim();
                if (key.equals(parsedKey)) {
                    String value = withoutInlineComment(trimmed.substring(eq + 1).trim());
                    return value.isBlank() ? null : stripQuotes(value);
                }
            }
        }
        return null;
    }

    private static String ensurePath(String baseUrl, String path) {
        String base = trimTrailingSlash(baseUrl);
        if (base == null) {
            throw new IllegalArgumentException("Invalid base URL");
        }
        if (base.endsWith(path)) {
            return base;
        }
        if (path.startsWith("/v1/") && base.endsWith("/v1")) {
            return base + path.substring("/v1".length());
        }
        return base + path;
    }

    private static String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.replaceAll("/+$", "");
    }

    private static String firstNotBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        if (b != null && !b.isBlank()) {
            return b;
        }
        return null;
    }

    private static ProbeRequest postJson(String url, List<Header> headers, String body, String label) {
        List<Header> allHeaders = new ArrayList<>();
        allHeaders.add(new Header("Content-Type", "application/json"));
        allHeaders.addAll(headers);
        return new ProbeRequest("POST", url, allHeaders, body, label);
    }

    private static List<Header> bearerHeaders(String apiKey) {
        return List.of(new Header("Authorization", "Bearer " + apiKey));
    }

    private static String openAiResponsesBody(String model) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("input", "ping");
        body.addProperty("max_output_tokens", 1);
        return GSON.toJson(body);
    }

    private static String openAiChatBody(String model) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("max_tokens", 1);

        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        message.addProperty("content", "ping");
        JsonArray messages = new JsonArray();
        messages.add(message);
        body.add("messages", messages);
        return GSON.toJson(body);
    }

    private static String claudeMessagesBody(String model) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("max_tokens", 1);

        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        message.addProperty("content", "ping");
        JsonArray messages = new JsonArray();
        messages.add(message);
        body.add("messages", messages);
        return GSON.toJson(body);
    }

    private static String geminiGenerateContentBody() {
        JsonObject textPart = new JsonObject();
        textPart.addProperty("text", "ping");
        JsonArray parts = new JsonArray();
        parts.add(textPart);

        JsonObject content = new JsonObject();
        content.add("parts", parts);
        JsonArray contents = new JsonArray();
        contents.add(content);

        JsonObject generationConfig = new JsonObject();
        generationConfig.addProperty("maxOutputTokens", 1);

        JsonObject body = new JsonObject();
        body.add("contents", contents);
        body.add("generationConfig", generationConfig);
        return GSON.toJson(body);
    }

    private static String resolveClaudeProbeModel(JsonObject env) {
        String model = getString(env, "ANTHROPIC_MODEL");
        if (model == null) {
            return firstNotBlank(
                    getString(env, "ANTHROPIC_DEFAULT_OPUS_MODEL"),
                    firstNotBlank(
                            getString(env, "ANTHROPIC_DEFAULT_SONNET_MODEL"),
                            getString(env, "ANTHROPIC_DEFAULT_HAIKU_MODEL")));
        }

        String normalized = model.trim().toLowerCase();
        if (normalized.equals("haiku") || normalized.startsWith("haiku[")) {
            return firstNotBlank(getString(env, "ANTHROPIC_DEFAULT_HAIKU_MODEL"), model);
        }
        if (normalized.equals("sonnet") || normalized.startsWith("sonnet[")) {
            return firstNotBlank(getString(env, "ANTHROPIC_DEFAULT_SONNET_MODEL"), model);
        }
        if (normalized.equals("opus") || normalized.startsWith("opus[")) {
            return firstNotBlank(getString(env, "ANTHROPIC_DEFAULT_OPUS_MODEL"), model);
        }
        return model;
    }

    private static String firstOpenCodeModel(JsonObject config) {
        if (config == null || !config.has("models") || !config.get("models").isJsonObject()) {
            return null;
        }
        JsonObject models = config.getAsJsonObject("models");
        for (String key : models.keySet()) {
            if (models.get(key).isJsonObject()) {
                String name = getString(models.getAsJsonObject(key), "name");
                if (name != null) {
                    return name;
                }
            }
            if (!key.isBlank()) {
                return key;
            }
        }
        return null;
    }

    private static String normalizeGeminiModelPath(String model) {
        String value = model.trim();
        if (value.startsWith("models/")) {
            return value;
        }
        return "models/" + value;
    }

    private static String stripQuotes(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if ((trimmed.startsWith("\"") && trimmed.endsWith("\""))
                || (trimmed.startsWith("'") && trimmed.endsWith("'"))) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static String withoutInlineComment(String value) {
        if (value == null) {
            return "";
        }
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
            } else if (c == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
            } else if (c == '#' && !inSingleQuote && !inDoubleQuote) {
                return value.substring(0, i).trim();
            }
        }
        return value.trim();
    }

    private static TestResult chooseBetterFailure(TestResult previous, TestResult current) {
        if (previous == null) {
            return current;
        }
        if (previous.statusCode() == 404 && current.statusCode() != 404) {
            return current;
        }
        if (current.statusCode() == 404 && previous.statusCode() != 404) {
            return previous;
        }
        return current;
    }

    private static long cost(long start) {
        return Math.max(0, System.currentTimeMillis() - start);
    }
}
