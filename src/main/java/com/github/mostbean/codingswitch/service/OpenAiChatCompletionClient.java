package com.github.mostbean.codingswitch.service;

import com.github.mostbean.codingswitch.model.AiCompletionRequest;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.http.HttpClient;
import java.util.Map;
import java.util.function.Consumer;

final class OpenAiChatCompletionClient implements AiCompletionClient {

    private static final Gson GSON = new Gson();

    @Override
    public String complete(AiCompletionRequest request) throws IOException, InterruptedException {
        HttpClient client = AiCompletionHttpSupport.createClient(request.profile());
        String response = AiCompletionHttpSupport.postJson(
            client,
            request.profile(),
            AiCompletionHttpSupport.ensurePath(request.profile().getBaseUrl(), "/v1/chat/completions"),
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
            AiCompletionHttpSupport.ensurePath(request.profile().getBaseUrl(), "/v1/chat/completions"),
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
        body.addProperty("max_completion_tokens", request.maxTokens());
        body.addProperty("temperature", 0.2);
        if (stream) {
            body.addProperty("stream", true);
        }

        JsonArray messages = new JsonArray();
        messages.add(message("system", request.systemPrompt()));
        messages.add(message("user", request.userPrompt()));
        body.add("messages", messages);
        return body;
    }

    private JsonObject message(String role, String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        message.addProperty("content", content);
        return message;
    }

    private String extractText(String response) {
        JsonObject root = JsonParser.parseString(response).getAsJsonObject();
        if (!root.has("choices") || !root.get("choices").isJsonArray() || root.getAsJsonArray("choices").isEmpty()) {
            return "";
        }
        JsonObject choice = root.getAsJsonArray("choices").get(0).getAsJsonObject();
        if (!choice.has("message") || !choice.get("message").isJsonObject()) {
            return "";
        }
        JsonObject message = choice.getAsJsonObject("message");
        if (!message.has("content") || message.get("content").isJsonNull()) {
            return "";
        }
        return message.get("content").getAsString();
    }

    private String extractDelta(String event) {
        JsonObject root = JsonParser.parseString(event).getAsJsonObject();
        if (!root.has("choices") || !root.get("choices").isJsonArray() || root.getAsJsonArray("choices").isEmpty()) {
            return "";
        }
        JsonObject choice = root.getAsJsonArray("choices").get(0).getAsJsonObject();
        if (!choice.has("delta") || !choice.get("delta").isJsonObject()) {
            return "";
        }
        JsonObject delta = choice.getAsJsonObject("delta");
        if (!delta.has("content") || delta.get("content").isJsonNull()) {
            return "";
        }
        return delta.get("content").getAsString();
    }
}
