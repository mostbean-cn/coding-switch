package com.github.mostbean.codingswitch.service;

import com.github.mostbean.codingswitch.model.AiCompletionRequest;
import java.io.IOException;
import java.util.function.Consumer;

interface AiCompletionClient {
    String complete(AiCompletionRequest request) throws IOException, InterruptedException;

    default void streamComplete(AiCompletionRequest request, Consumer<String> onDelta)
        throws IOException, InterruptedException {
        String completion = complete(request);
        if (completion != null && !completion.isEmpty()) {
            onDelta.accept(completion);
        }
    }
}
