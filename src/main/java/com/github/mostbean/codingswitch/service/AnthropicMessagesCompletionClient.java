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

final class AnthropicMessagesCompletionClient implements AiCompletionClient {

    private static final Gson GSON = new Gson();

    @Override
    public String complete(AiCompletionRequest request) throws IOException, InterruptedException {
        HttpClient client = AiCompletionHttpSupport.createClient(request.profile());
        String response = AiCompletionHttpSupport.postJson(
            client,
            request.profile(),
            AiCompletionHttpSupport.ensurePath(request.profile().getBaseUrl(), "/v1/messages"),
            Map.of(
                "x-api-key", request.apiKey(),
                "anthropic-version", "2023-06-01"
            ),
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
            AiCompletionHttpSupport.ensurePath(request.profile().getBaseUrl(), "/v1/messages"),
            Map.of(
                "x-api-key", request.apiKey(),
                "anthropic-version", "2023-06-01"
            ),
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
        body.addProperty("system", request.systemPrompt());
        body.addProperty("temperature", 0.2);
        if (stream) {
            body.addProperty("stream", true);
        }

        JsonArray messages = new JsonArray();
        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", request.userPrompt());
        messages.add(user);
        body.add("messages", messages);
        return body;
    }

    private String extractText(String response) {
        JsonObject root = JsonParser.parseString(response).getAsJsonObject();
        if (!root.has("content") || !root.get("content").isJsonArray()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (JsonElement item : root.getAsJsonArray("content")) {
            if (!item.isJsonObject()) {
                continue;
            }
            JsonObject object = item.getAsJsonObject();
            if (object.has("text") && !object.get("text").isJsonNull()) {
                out.append(object.get("text").getAsString());
            }
        }
        return out.toString();
    }

    private String extractDelta(String event) {
        JsonObject root = JsonParser.parseString(event).getAsJsonObject();
        if (!"content_block_delta".equals(getString(root, "type"))) {
            return "";
        }
        if (!root.has("delta") || !root.get("delta").isJsonObject()) {
            return "";
        }
        JsonObject delta = root.getAsJsonObject("delta");
        if (!"text_delta".equals(getString(delta, "type"))) {
            return "";
        }
        String text = getString(delta, "text");
        return text == null ? "" : text;
    }

    private String getString(JsonObject object, String key) {
        if (!object.has(key) || object.get(key).isJsonNull()) {
            return null;
        }
        return object.get(key).getAsString();
    }
}
