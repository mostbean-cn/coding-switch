package com.github.mostbean.codingswitch.service;

import com.github.mostbean.codingswitch.model.CliType;
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
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(12);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

    public record TestResult(boolean success, int statusCode, String message, long durationMs) {
    }

    private record ProbeRequest(String url, List<Header> headers) {
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
                lastFail = result;
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
                    .timeout(REQUEST_TIMEOUT)
                    .GET();
            for (Header header : probe.headers()) {
                if (header.value() != null && !header.value().isBlank()) {
                    builder.header(header.key(), header.value());
                }
            }

            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            int code = response.statusCode();
            if (code >= 200 && code < 300) {
                return new TestResult(true, code, "OK", 0);
            }
            String body = response.body() == null ? "" : response.body();
            body = body.length() > 180 ? body.substring(0, 180) + "..." : body;
            return new TestResult(false, code, "HTTP " + code + (body.isBlank() ? "" : " - " + body), 0);
        } catch (IOException e) {
            return new TestResult(false, -1, "I/O error: " + e.getMessage(), 0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new TestResult(false, -1, "Interrupted", 0);
        } catch (Exception e) {
            return new TestResult(false, -1, e.getMessage(), 0);
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

        List<ProbeRequest> probes = new ArrayList<>();
        probes.add(new ProbeRequest(
                ensurePath(baseUrl, "/v1/models"),
                List.of(
                        new Header("x-api-key", token),
                        new Header("anthropic-version", "2023-06-01"))));
        probes.add(new ProbeRequest(
                ensurePath(baseUrl, "/models"),
                List.of(new Header("Authorization", "Bearer " + token))));
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

        String baseUrl = parseBaseUrlFromToml(config != null ? getString(config, "config") : null);
        if (baseUrl == null) {
            baseUrl = "https://api.openai.com/v1";
        }

        return List.of(new ProbeRequest(
                ensurePath(baseUrl, "/models"),
                List.of(new Header("Authorization", "Bearer " + apiKey))));
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

        String modelsUrl = ensurePath(baseUrl, "/models")
                + "?key=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8);
        return List.of(new ProbeRequest(modelsUrl, List.of()));
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

        return List.of(new ProbeRequest(
                ensurePath(baseUrl, "/models"),
                List.of(new Header("Authorization", "Bearer " + apiKey))));
    }

    private static String getString(JsonObject json, String key) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
            return null;
        }
        String value = json.get(key).getAsString();
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String parseBaseUrlFromToml(String toml) {
        if (toml == null || toml.isBlank()) {
            return null;
        }
        for (String line : toml.split("\\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("base_url")) {
                int eq = trimmed.indexOf('=');
                if (eq > 0) {
                    String value = trimmed.substring(eq + 1).trim().replace("\"", "");
                    return value.isBlank() ? null : value;
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

    private static long cost(long start) {
        return Math.max(0, System.currentTimeMillis() - start);
    }
}
