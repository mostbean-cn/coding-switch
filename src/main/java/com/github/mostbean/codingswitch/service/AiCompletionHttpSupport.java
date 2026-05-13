package com.github.mostbean.codingswitch.service;

import com.github.mostbean.codingswitch.model.AiModelProfile;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;

final class AiCompletionHttpSupport {

    private AiCompletionHttpSupport() {
    }

    static HttpClient createClient(AiModelProfile profile) {
        return HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(profile.getTimeoutSeconds()))
            .build();
    }

    static String postJson(HttpClient client, AiModelProfile profile, String url, Map<String, String> headers, String body)
        throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(profile.getTimeoutSeconds()))
            .header("Content-Type", "application/json");
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getValue() != null && !entry.getValue().isBlank()) {
                builder.header(entry.getKey(), entry.getValue());
            }
        }
        addCustomHeaders(builder, profile.getHeadersJson());
        HttpResponse<String> response = client.send(
            builder.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
        int statusCode = response.statusCode();
        String responseBody = response.body() == null ? "" : response.body();
        if (statusCode >= 200 && statusCode < 300) {
            return responseBody;
        }
        String detail = responseBody.length() > 240 ? responseBody.substring(0, 240) + "..." : responseBody;
        throw new IOException("HTTP " + statusCode + (detail.isBlank() ? "" : ": " + detail));
    }

    static void postJsonStream(
        HttpClient client,
        AiModelProfile profile,
        String url,
        Map<String, String> headers,
        String body,
        Consumer<String> onEvent
    ) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(profile.getTimeoutSeconds()))
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream");
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getValue() != null && !entry.getValue().isBlank()) {
                builder.header(entry.getKey(), entry.getValue());
            }
        }
        addCustomHeaders(builder, profile.getHeadersJson());
        HttpResponse<InputStream> response = client.send(
            builder.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
            HttpResponse.BodyHandlers.ofInputStream()
        );
        int statusCode = response.statusCode();
        if (statusCode < 200 || statusCode >= 300) {
            String responseBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
            String detail = responseBody.length() > 240 ? responseBody.substring(0, 240) + "..." : responseBody;
            throw new IOException("HTTP " + statusCode + (detail.isBlank() ? "" : ": " + detail));
        }
        readServerSentEvents(response.body(), onEvent);
    }

    static String ensurePath(String baseUrl, String path) {
        String base = trimTrailingSlash(baseUrl);
        if (base == null) {
            throw new IllegalArgumentException("Invalid Base URL");
        }
        if (base.endsWith(path)) {
            return base;
        }
        if (path.startsWith("/v1/") && base.endsWith("/v1")) {
            return base + path.substring("/v1".length());
        }
        return base + path;
    }

    static String trimCompletion(String value) {
        if (value == null) {
            return "";
        }
        String text = value.replace("\r\n", "\n").stripTrailing();
        int fenceStart = text.indexOf("```");
        if (fenceStart >= 0) {
            int contentStart = text.indexOf('\n', fenceStart);
            if (contentStart >= 0) {
                int fenceEnd = text.indexOf("\n```", contentStart + 1);
                if (fenceEnd >= 0) {
                    return text.substring(contentStart + 1, fenceEnd).stripTrailing();
                }
                if (text.substring(0, fenceStart).isBlank()) {
                    return text.substring(contentStart + 1).stripTrailing();
                }
            }
        }
        return text;
    }

    private static void addCustomHeaders(HttpRequest.Builder builder, String headersJson) {
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

    private static void readServerSentEvents(InputStream inputStream, Consumer<String> onEvent) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            StringBuilder data = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    emitEvent(data, onEvent);
                    continue;
                }
                if (line.startsWith(":")) {
                    continue;
                }
                if (line.startsWith("data:")) {
                    if (data.length() > 0) {
                        data.append('\n');
                    }
                    data.append(line.substring("data:".length()).trim());
                }
            }
            emitEvent(data, onEvent);
        }
    }

    private static void emitEvent(StringBuilder data, Consumer<String> onEvent) {
        if (data.isEmpty()) {
            return;
        }
        String event = data.toString();
        data.setLength(0);
        if (!"[DONE]".equals(event)) {
            onEvent.accept(event);
        }
    }

    private static String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.replaceAll("/+$", "");
    }
}
