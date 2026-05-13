package com.github.mostbean.codingswitch.service;

import com.github.mostbean.codingswitch.model.AiModelFormat;
import com.github.mostbean.codingswitch.model.AiModelProfile;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Service(Service.Level.APP)
public final class AiModelConnectionTestService {

    private static final Gson GSON = new Gson();

    public record TestResult(boolean success, int statusCode, String message, long durationMs) {
    }

    public record ModelListResult(boolean success, int statusCode, String message, long durationMs, List<String> models) {
    }

    private record ProbeRequest(String method, String url, List<Header> headers, String body, String label) {
    }

    private record Header(String key, String value) {
    }

    public static AiModelConnectionTestService getInstance() {
        return ApplicationManager.getApplication().getService(AiModelConnectionTestService.class);
    }

    public TestResult test(AiModelProfile profile, String apiKey) {
        long start = System.currentTimeMillis();
        try {
            validate(profile, apiKey, true);
            HttpClient client = AiCompletionHttpSupport.createClient(profile);
            TestResult lastFail = null;
            for (ProbeRequest probe : buildProbeRequests(profile, apiKey)) {
                TestResult result = doProbe(client, profile, probe);
                if (result.success()) {
                    return new TestResult(true, result.statusCode(), result.message(), cost(start));
                }
                lastFail = chooseBetterFailure(lastFail, result);
            }
            if (lastFail != null) {
                return new TestResult(false, lastFail.statusCode(), lastFail.message(), cost(start));
            }
            return new TestResult(false, -1, "未找到可用的测试端点", cost(start));
        } catch (IllegalArgumentException e) {
            return new TestResult(false, -1, e.getMessage(), cost(start));
        } catch (Exception e) {
            return new TestResult(false, -1, e.getMessage(), cost(start));
        }
    }

    public ModelListResult listModels(AiModelProfile profile, String apiKey) {
        long start = System.currentTimeMillis();
        try {
            validate(profile, apiKey, false);
            HttpClient client = AiCompletionHttpSupport.createClient(profile);
            TestResult lastFail = null;
            for (ProbeRequest probe : buildModelListRequests(profile, apiKey)) {
                ModelListResult result = doModelListRequest(client, profile, probe, start);
                if (result.success()) {
                    return result;
                }
                lastFail = chooseBetterFailure(
                    lastFail,
                    new TestResult(false, result.statusCode(), result.message(), 0)
                );
            }
            if (lastFail != null) {
                return new ModelListResult(false, lastFail.statusCode(), lastFail.message(), cost(start), List.of());
            }
            return new ModelListResult(false, -1, "未找到可用的模型检测端点", cost(start), List.of());
        } catch (IllegalArgumentException e) {
            return new ModelListResult(false, -1, e.getMessage(), cost(start), List.of());
        } catch (Exception e) {
            return new ModelListResult(false, -1, e.getMessage(), cost(start), List.of());
        }
    }

    private TestResult doProbe(HttpClient client, AiModelProfile profile, ProbeRequest probe) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(probe.url()))
                .timeout(Duration.ofSeconds(profile.getTimeoutSeconds()));
            addHeaders(builder, profile, probe.headers());
            HttpRequest request;
            if ("POST".equalsIgnoreCase(probe.method())) {
                request = builder.POST(HttpRequest.BodyPublishers.ofString(probe.body(), StandardCharsets.UTF_8))
                    .build();
            } else {
                request = builder.GET().build();
            }

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int code = response.statusCode();
            if (code >= 200 && code < 300) {
                return new TestResult(true, code, probe.label() + " OK", 0);
            }
            return new TestResult(false, code, probe.label() + " HTTP " + code + responseDetail(response.body()), 0);
        } catch (IOException e) {
            return new TestResult(false, -1, probe.label() + " I/O 错误: " + e.getMessage(), 0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new TestResult(false, -1, probe.label() + " 已中断", 0);
        } catch (Exception e) {
            return new TestResult(false, -1, probe.label() + " " + e.getMessage(), 0);
        }
    }

    private ModelListResult doModelListRequest(
        HttpClient client,
        AiModelProfile profile,
        ProbeRequest probe,
        long start
    ) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(probe.url()))
                .timeout(Duration.ofSeconds(profile.getTimeoutSeconds()))
                .GET();
            addHeaders(builder, profile, probe.headers());

            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int code = response.statusCode();
            if (code >= 200 && code < 300) {
                List<String> models = parseModelIds(response.body());
                if (models.isEmpty()) {
                    return new ModelListResult(false, code, probe.label() + " 未返回模型", cost(start), List.of());
                }
                return new ModelListResult(true, code, probe.label() + " OK", cost(start), models);
            }
            return new ModelListResult(
                false,
                code,
                probe.label() + " HTTP " + code + responseDetail(response.body()),
                cost(start),
                List.of()
            );
        } catch (IOException e) {
            return new ModelListResult(false, -1, probe.label() + " I/O 错误: " + e.getMessage(), cost(start), List.of());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ModelListResult(false, -1, probe.label() + " 已中断", cost(start), List.of());
        } catch (Exception e) {
            return new ModelListResult(false, -1, probe.label() + " " + e.getMessage(), cost(start), List.of());
        }
    }

    private List<ProbeRequest> buildProbeRequests(AiModelProfile profile, String apiKey) {
        return switch (profile.getFormat()) {
            case OPENAI_RESPONSES -> List.of(postJson(
                AiCompletionHttpSupport.ensurePath(profile.getBaseUrl(), "/v1/responses"),
                bearerHeaders(apiKey),
                openAiResponsesBody(profile.getModel()),
                "OpenAI Responses"
            ));
            case OPENAI_CHAT_COMPLETIONS -> List.of(postJson(
                AiCompletionHttpSupport.ensurePath(profile.getBaseUrl(), "/v1/chat/completions"),
                bearerHeaders(apiKey),
                openAiChatBody(profile.getModel()),
                "OpenAI Chat Completions"
            ));
            case FIM_CHAT_COMPLETIONS -> List.of(postJson(
                AiCompletionHttpSupport.ensurePath(profile.getBaseUrl(), "/chat/completions"),
                bearerHeaders(apiKey),
                fimChatBody(profile.getModel()),
                "FIM Chat Completions"
            ));
            case DEEPSEEK_FIM_COMPLETIONS -> List.of(postJson(
                AiCompletionHttpSupport.ensurePath(profile.getBaseUrl(), "/completions"),
                bearerHeaders(apiKey),
                deepSeekFimBody(profile.getModel()),
                "FIM Completions"
            ));
            case ANTHROPIC_MESSAGES -> {
                String body = anthropicMessagesBody(profile.getModel());
                yield List.of(
                    postJson(
                        AiCompletionHttpSupport.ensurePath(profile.getBaseUrl(), "/v1/messages"),
                        anthropicHeaders(apiKey),
                        body,
                        "Anthropic Messages"
                    ),
                    postJson(
                        AiCompletionHttpSupport.ensurePath(profile.getBaseUrl(), "/messages"),
                        anthropicHeaders(apiKey),
                        body,
                        "Anthropic Messages"
                    )
                );
            }
        };
    }

    private List<ProbeRequest> buildModelListRequests(AiModelProfile profile, String apiKey) {
        if (profile.getFormat() == AiModelFormat.ANTHROPIC_MESSAGES) {
            return List.of(
                get(
                    AiCompletionHttpSupport.ensurePath(profile.getBaseUrl(), "/v1/models"),
                    anthropicHeaders(apiKey),
                    "Anthropic Models"
                ),
                get(
                    AiCompletionHttpSupport.ensurePath(profile.getBaseUrl(), "/models"),
                    anthropicHeaders(apiKey),
                    "Anthropic Models"
                )
            );
        }
        if (profile.getFormat() == AiModelFormat.DEEPSEEK_FIM_COMPLETIONS) {
            String baseUrl = removeTrailingBetaPath(profile.getBaseUrl());
            return List.of(
                get(
                    AiCompletionHttpSupport.ensurePath(baseUrl, "/models"),
                    bearerHeaders(apiKey),
                    "DeepSeek Models"
                ),
                get(
                    AiCompletionHttpSupport.ensurePath(profile.getBaseUrl(), "/models"),
                    bearerHeaders(apiKey),
                    "DeepSeek Beta Models"
                )
            );
        }
        if (profile.getFormat() == AiModelFormat.FIM_CHAT_COMPLETIONS) {
            return List.of(get(
                AiCompletionHttpSupport.ensurePath(profile.getBaseUrl(), "/models"),
                bearerHeaders(apiKey),
                "FIM Chat Models"
            ));
        }
        return List.of(get(
            AiCompletionHttpSupport.ensurePath(profile.getBaseUrl(), "/v1/models"),
            bearerHeaders(apiKey),
            "OpenAI Models"
        ));
    }

    private void validate(AiModelProfile profile, String apiKey, boolean requireModel) {
        if (profile == null) {
            throw new IllegalArgumentException("模型配置为空");
        }
        if (profile.getBaseUrl().isBlank()) {
            throw new IllegalArgumentException("请填写 Base URL");
        }
        if (requireModel && profile.getModel().isBlank()) {
            throw new IllegalArgumentException("请填写模型名称");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("请填写 API Key");
        }
    }

    private void addHeaders(HttpRequest.Builder builder, AiModelProfile profile, List<Header> headers) {
        for (Header header : headers) {
            if (header.value() != null && !header.value().isBlank()) {
                builder.header(header.key(), header.value());
            }
        }
        addCustomHeaders(builder, profile.getHeadersJson());
    }

    private void addCustomHeaders(HttpRequest.Builder builder, String headersJson) {
        if (headersJson == null || headersJson.isBlank()) {
            return;
        }
        JsonObject headers = JsonParser.parseString(headersJson).getAsJsonObject();
        for (String key : headers.keySet()) {
            if (!headers.get(key).isJsonPrimitive()) {
                continue;
            }
            String value = headers.get(key).getAsString();
            if (value != null && !value.isBlank()) {
                builder.header(key, value);
            }
        }
    }

    private static ProbeRequest postJson(String url, List<Header> headers, String body, String label) {
        List<Header> allHeaders = new ArrayList<>();
        allHeaders.add(new Header("Content-Type", "application/json"));
        allHeaders.addAll(headers);
        return new ProbeRequest("POST", url, allHeaders, body, label);
    }

    private static ProbeRequest get(String url, List<Header> headers, String label) {
        return new ProbeRequest("GET", url, headers, null, label);
    }

    private static List<Header> bearerHeaders(String apiKey) {
        return List.of(new Header("Authorization", "Bearer " + apiKey));
    }

    private static List<Header> anthropicHeaders(String apiKey) {
        return List.of(
            new Header("x-api-key", apiKey),
            new Header("anthropic-version", "2023-06-01")
        );
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
        body.add("messages", oneUserMessage());
        return GSON.toJson(body);
    }

    private static String fimChatBody(String model) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("max_tokens", 1);
        body.addProperty("prefix", "def ping():\n    ");
        body.addProperty("suffix", "\n");
        body.add("messages", oneUserMessage());
        return GSON.toJson(body);
    }

    private static String deepSeekFimBody(String model) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("prompt", "def ping():\n    ");
        body.addProperty("suffix", "\n");
        body.addProperty("max_tokens", 1);
        body.addProperty("temperature", 0.2);
        return GSON.toJson(body);
    }

    private static String anthropicMessagesBody(String model) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("max_tokens", 1);
        body.add("messages", oneUserMessage());
        return GSON.toJson(body);
    }

    private static JsonArray oneUserMessage() {
        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        message.addProperty("content", "ping");
        JsonArray messages = new JsonArray();
        messages.add(message);
        return messages;
    }

    private static List<String> parseModelIds(String body) {
        if (body == null || body.isBlank()) {
            return List.of();
        }
        JsonElement root = JsonParser.parseString(body);
        List<String> models = new ArrayList<>();
        collectModelIds(root, models);
        return models.stream()
            .map(String::trim)
            .filter(value -> !value.isBlank())
            .distinct()
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .toList();
    }

    private static void collectModelIds(JsonElement element, List<String> out) {
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) {
                collectModelIds(item, out);
            }
            return;
        }
        if (!element.isJsonObject()) {
            return;
        }

        JsonObject object = element.getAsJsonObject();
        addModelId(object, out);
        collectNamedArray(object, "data", out);
        collectNamedArray(object, "models", out);
    }

    private static void collectNamedArray(JsonObject object, String key, List<String> out) {
        if (object.has(key) && object.get(key).isJsonArray()) {
            collectModelIds(object.get(key), out);
        }
    }

    private static void addModelId(JsonObject object, List<String> out) {
        String value = firstNotBlank(
            getString(object, "id"),
            firstNotBlank(getString(object, "name"), getString(object, "model"))
        );
        if (value == null) {
            return;
        }
        if (value.startsWith("models/")) {
            value = value.substring("models/".length());
        }
        out.add(value);
    }

    private static String getString(JsonObject json, String key) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
            return null;
        }
        String value = json.get(key).getAsString();
        return value == null || value.isBlank() ? null : value.trim();
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

    private static String removeTrailingBetaPath(String baseUrl) {
        String value = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        if (value.endsWith("/beta")) {
            return value.substring(0, value.length() - "/beta".length());
        }
        return value;
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

    private static String responseDetail(String responseBody) {
        String body = responseBody == null ? "" : responseBody;
        body = body.length() > 180 ? body.substring(0, 180) + "..." : body;
        return body.isBlank() ? "" : " - " + body;
    }

    private static long cost(long start) {
        return Math.max(0, System.currentTimeMillis() - start);
    }
}
