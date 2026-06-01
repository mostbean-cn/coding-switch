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
        AiCompletionHttpSupport.postJsonStreamWithRetry(
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
        body.addProperty("max_tokens", request.maxTokens());
        body.add("stop_sequences", FimStopSequences.create(request));
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
        String content = extractContentText(root);
        if (!content.isBlank()) {
            return content;
        }
        String completion = getString(root, "completion");
        if (completion != null) {
            return completion;
        }
        if (root.has("message") && root.get("message").isJsonObject()) {
            return extractContentText(root.getAsJsonObject("message"));
        }
        return "";
    }

    private String extractDelta(String event) {
        JsonObject root = JsonParser.parseString(event).getAsJsonObject();
        String anthropicDelta = extractAnthropicDelta(root);
        if (!anthropicDelta.isEmpty()) {
            return anthropicDelta;
        }
        return extractOpenAiCompatibleDelta(root);
    }

    private String extractAnthropicDelta(JsonObject root) {
        String type = getString(root, "type");
        if ("content_block_delta".equals(type)) {
            if (!root.has("delta") || !root.get("delta").isJsonObject()) {
                return "";
            }
            JsonObject delta = root.getAsJsonObject("delta");
            String deltaType = getString(delta, "type");
            if (deltaType != null && !"text_delta".equals(deltaType)) {
                return "";
            }
            return firstTextValue(delta);
        }
        if ("content_block_start".equals(type)
            && root.has("content_block")
            && root.get("content_block").isJsonObject()) {
            JsonObject contentBlock = root.getAsJsonObject("content_block");
            if (!"text".equals(getString(contentBlock, "type"))) {
                return "";
            }
            return firstTextValue(contentBlock);
        }
        return "";
    }

    private String extractOpenAiCompatibleDelta(JsonObject root) {
        if (!root.has("choices") || !root.get("choices").isJsonArray() || root.getAsJsonArray("choices").isEmpty()) {
            return "";
        }
        JsonElement first = root.getAsJsonArray("choices").get(0);
        if (!first.isJsonObject()) {
            return "";
        }
        JsonObject choice = first.getAsJsonObject();
        if (!choice.has("delta") || !choice.get("delta").isJsonObject()) {
            return "";
        }
        return firstTextValue(choice.getAsJsonObject("delta"));
    }

    private String extractContentText(JsonObject root) {
        if (!root.has("content") || root.get("content").isJsonNull()) {
            return "";
        }
        JsonElement content = root.get("content");
        if (content.isJsonPrimitive()) {
            return content.getAsString();
        }
        if (!content.isJsonArray()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (JsonElement item : content.getAsJsonArray()) {
            if (!item.isJsonObject()) {
                continue;
            }
            JsonObject object = item.getAsJsonObject();
            String type = getString(object, "type");
            if (type != null && !"text".equals(type)) {
                continue;
            }
            String text = firstTextValue(object);
            if (!text.isEmpty()) {
                out.append(text);
            }
        }
        return out.toString();
    }

    private String firstTextValue(JsonObject object) {
        String text = getString(object, "text");
        if (text != null) {
            return text;
        }
        String content = getString(object, "content");
        return content == null ? "" : content;
    }

    private String getString(JsonObject object, String key) {
        if (!object.has(key) || object.get(key).isJsonNull()) {
            return null;
        }
        return object.get(key).getAsString();
    }
}
