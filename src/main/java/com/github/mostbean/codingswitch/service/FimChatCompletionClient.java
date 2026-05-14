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

final class FimChatCompletionClient implements AiCompletionClient {

    private static final Gson GSON = new Gson();

    @Override
    public String complete(AiCompletionRequest request) throws IOException, InterruptedException {
        HttpClient client = AiCompletionHttpSupport.createClient(request.profile());
        String response = AiCompletionHttpSupport.postJson(
            client,
            request.profile(),
            AiCompletionHttpSupport.ensurePath(request.profile().getBaseUrl(), "/chat/completions"),
            Map.of("Authorization", "Bearer " + request.apiKey()),
            GSON.toJson(createBody(request, false))
        );
        return AiCompletionHttpSupport.trimCompletion(extractText(response));
    }

    @Override
    public void streamComplete(AiCompletionRequest request, Consumer<String> onDelta)
        throws IOException, InterruptedException {
        HttpClient client = AiCompletionHttpSupport.createClient(request.profile());
        StringBuilder completion = new StringBuilder();
        AiCompletionHttpSupport.postJsonStream(
            client,
            request.profile(),
            AiCompletionHttpSupport.ensurePath(request.profile().getBaseUrl(), "/chat/completions"),
            Map.of("Authorization", "Bearer " + request.apiKey()),
            GSON.toJson(createBody(request, true)),
            event -> {
                String delta = extractDelta(event);
                if (!delta.isEmpty()) {
                    completion.append(delta);
                }
            }
        );
        String text = AiCompletionHttpSupport.trimCompletion(completion.toString());
        if (!text.isBlank()) {
            onDelta.accept(text);
        }
    }

    private JsonObject createBody(AiCompletionRequest request, boolean stream) {
        JsonObject body = new JsonObject();
        body.addProperty("model", request.profile().getModel());
        body.add("messages", messages(request));
        body.addProperty("prefix", request.fimPrefix());
        if (!request.fimSuffix().isBlank()) {
            body.addProperty("suffix", request.fimSuffix());
        }
        body.addProperty("stream", stream);
        body.addProperty("max_tokens", request.maxTokens());
        body.add("stop", FimStopSequences.create(request));
        body.addProperty("temperature", 0.2);
        return body;
    }

    private JsonArray messages(AiCompletionRequest request) {
        JsonArray messages = new JsonArray();
        JsonObject system = new JsonObject();
        system.addProperty("role", "system");
        system.addProperty("content", request.systemPrompt());
        messages.add(system);

        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty(
            "content",
            request.userPrompt()
                + "\n\nUse the provided prefix and suffix to complete only the text at the caret. "
                + "Return only the inserted code/text. Do not add markdown fences or explanations."
        );
        messages.add(user);
        return messages;
    }

    private String extractText(String response) {
        JsonObject root = JsonParser.parseString(response).getAsJsonObject();
        JsonObject choice = firstChoice(root);
        if (choice == null || !choice.has("message") || !choice.get("message").isJsonObject()) {
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
        JsonObject choice = firstChoice(root);
        if (choice == null || !choice.has("delta") || !choice.get("delta").isJsonObject()) {
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
