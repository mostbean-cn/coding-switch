package com.github.mostbean.codingswitch.model;

public record AiCompletionRequest(
    AiModelProfile profile,
    String apiKey,
    String systemPrompt,
    String userPrompt,
    String fimPrefix,
    String fimSuffix
) {
    public AiCompletionRequest {
        fimPrefix = fimPrefix == null ? "" : fimPrefix;
        fimSuffix = fimSuffix == null ? "" : fimSuffix;
    }
}
