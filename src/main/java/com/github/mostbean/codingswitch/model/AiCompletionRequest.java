package com.github.mostbean.codingswitch.model;

public record AiCompletionRequest(
    AiModelProfile profile,
    String apiKey,
    String systemPrompt,
    String userPrompt,
    AiCompletionLengthLevel lengthLevel,
    int maxTokens
) {
}
