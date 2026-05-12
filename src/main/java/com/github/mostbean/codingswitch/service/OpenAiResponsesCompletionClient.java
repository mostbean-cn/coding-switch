package com.github.mostbean.codingswitch.service;

import com.github.mostbean.codingswitch.model.AiCompletionRequest;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.http.HttpClient;
import java.util.Map;
import java.util.function.Consumer;

final class OpenAiResponsesCompletionClient implements AiCompletionClient {

    private static final Gson GSON = new Gson();

    @Override
    public String complete(AiCompletionRequest request) throws IOException, InterruptedException {
        HttpClient client = AiCompletionHttpSupport.createClient(request.profile());
        String response = AiCompletionHttpSupport.postJson(
            client,
            request.profile(),
            AiCompletionHttpSupport.ensurePath(request.profile().getBaseUrl(), "/v1/responses"),
            Map.of("Authorization", "Bearer " + request.apiKey()),
            GSON.toJson(createBody(request, false))
        );
        return AiCompletionHttpSupport.trimCompletion(extractText(response));
    }

    @Override
    public void streamComplete(AiCompletionRequest request, Consumer<String> onDelta)
        throws IOException, InterruptedException {
        HttpClient client = AiCompletionHttpSupport.createClient(request.profile());
        AiCompletionHttpSupport.postJsonStream(
            client,
            request.profile(),
            AiCompletionHttpSupport.ensurePath(request.profile().getBaseUrl(), "/v1/responses"),
            Map.of("Authorization", "Bearer " + request.apiKey()),
            GSON.toJson(createBody(request, true)),
            event -> {
                String delta = extractDelta(event);
                if (!delta.isEmpty()) {
                    onDelta.accept(delta);
                }
            }
        );
    }

    private JsonObject createBody(AiCompletionRequest request, boolean stream) {
        JsonObject body = new JsonObject();
        body.addProperty("model", request.profile().getModel());
        body.addProperty("instructions", request.systemPrompt());
        body.addProperty("input", request.userPrompt());
        body.addProperty("max_output_tokens", request.maxTokens());
        body.addProperty("temperature", 0.2);
        if (stream) {
            body.addProperty("stream", true);
        }
        return body;
    }

    private String extractText(String response) {
        JsonObject root = JsonParser.parseString(response).getAsJsonObject();
        String direct = getString(root, "output_text");
        if (direct != null) {
            return direct;
        }
        if (!root.has("output") || !root.get("output").isJsonArray()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (JsonElement item : root.getAsJsonArray("output")) {
            if (!item.isJsonObject()) {
                continue;
            }
            JsonObject object = item.getAsJsonObject();
            JsonArray content = object.has("content") && object.get("content").isJsonArray()
                ? object.getAsJsonArray("content")
                : new JsonArray();
            for (JsonElement contentItem : content) {
                if (contentItem.isJsonObject()) {
                    String text = getString(contentItem.getAsJsonObject(), "text");
                    if (text != null) {
                        out.append(text);
                    }
                }
            }
        }
        return out.toString();
    }

    private String extractDelta(String event) {
        JsonObject root = JsonParser.parseString(event).getAsJsonObject();
        String type = getString(root, "type");
        if ("response.output_text.delta".equals(type) || "response.text.delta".equals(type)) {
            String delta = getString(root, "delta");
            return delta == null ? "" : delta;
        }
        return "";
    }

    private String getString(JsonObject object, String key) {
        if (!object.has(key) || object.get(key).isJsonNull()) {
            return null;
        }
        return object.get(key).getAsString();
    }
}
