package com.github.mostbean.codingswitch.service;

import com.github.mostbean.codingswitch.model.AiCompletionRequest;
import java.io.IOException;

interface AiCompletionClient {
    String complete(AiCompletionRequest request) throws IOException, InterruptedException;
}
