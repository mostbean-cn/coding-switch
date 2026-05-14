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

final class FimCompletionClient implements AiCompletionClient {

    private static final Gson GSON = new Gson();

    @Override
    public String complete(AiCompletionRequest request) throws IOException, InterruptedException {
        HttpClient client = AiCompletionHttpSupport.createClient(request.profile());
        String response = AiCompletionHttpSupport.postJson(
            client,
            request.profile(),
            AiCompletionHttpSupport.ensurePath(request.profile().getBaseUrl(), "/completions"),
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
            AiCompletionHttpSupport.ensurePath(request.profile().getBaseUrl(), "/completions"),
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
        body.addProperty("prompt", promptWithGuidance(request));
        if (!request.fimSuffix().isBlank()) {
            body.addProperty("suffix", request.fimSuffix());
        }
        body.addProperty("temperature", 0.2);
        if (stream) {
            body.addProperty("stream", true);
        }
        return body;
    }

    private String promptWithGuidance(AiCompletionRequest request) {
        String guidance = request.systemPrompt();
        String prefix = request.fimPrefix();
        if (guidance == null || guidance.isBlank()) {
            return prefix;
        }
        return "/*\n"
            + "Coding Switch completion guidance:\n"
            + guidance.strip()
            + "\n*/\n\n"
            + prefix;
    }

    private String extractText(String response) {
        JsonObject root = JsonParser.parseString(response).getAsJsonObject();
        JsonObject choice = firstChoice(root);
        if (choice == null || !choice.has("text") || choice.get("text").isJsonNull()) {
            return "";
        }
        return choice.get("text").getAsString();
    }

    private String extractDelta(String event) {
        JsonObject root = JsonParser.parseString(event).getAsJsonObject();
        JsonObject choice = firstChoice(root);
        if (choice == null) {
            return "";
        }
        if (choice.has("text") && !choice.get("text").isJsonNull()) {
            return choice.get("text").getAsString();
        }
        if (!choice.has("delta") || !choice.get("delta").isJsonObject()) {
            return "";
        }
        JsonObject delta = choice.getAsJsonObject("delta");
        if (!delta.has("content") || delta.get("content").isJsonNull()) {
            return "";
        }
        return delta.get("content").getAsString();
    }

    private JsonObject firstChoice(JsonObject root) {
        if (!root.has("choices") || !root.get("choices").isJsonArray()) {
            return null;
        }
        JsonArray choices = root.getAsJsonArray("choices");
        if (choices.isEmpty() || !choices.get(0).isJsonObject()) {
            return null;
        }
        return choices.get(0).getAsJsonObject();
    }
}
