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
            GSON.toJson(createBody(request))
        );
        return AiCompletionHttpSupport.trimCompletion(extractText(response));
    }

    private JsonObject createBody(AiCompletionRequest request) {
        JsonObject body = new JsonObject();
        body.addProperty("model", request.profile().getModel());
        body.addProperty("system", request.systemPrompt());
        body.addProperty("max_tokens", request.maxTokens());
        body.addProperty("temperature", 0.2);

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
}
