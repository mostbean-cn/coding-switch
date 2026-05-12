package com.github.mostbean.codingswitch.service;

import com.github.mostbean.codingswitch.model.AiCompletionRequest;
import com.github.mostbean.codingswitch.model.AiCompletionLengthLevel;
import com.github.mostbean.codingswitch.model.AiCompletionTriggerMode;
import com.github.mostbean.codingswitch.model.AiModelFormat;
import com.github.mostbean.codingswitch.model.AiModelProfile;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service(Service.Level.APP)
public final class AiCompletionService {

    private static final long AUTO_COMPLETION_COOLDOWN_MS = 1200;
    private static final long MANUAL_COMPLETION_COOLDOWN_MS = 350;
    private final Map<String, Long> inFlightCompletionKeys = new ConcurrentHashMap<>();
    private long lastAutoCompletionRequestMs = 0L;
    private long lastManualCompletionRequestMs = 0L;

    public static AiCompletionService getInstance() {
        return ApplicationManager.getApplication().getService(AiCompletionService.class);
    }

    public Optional<String> complete(Project project, Editor editor, AiCompletionTriggerMode triggerMode)
        throws IOException, InterruptedException {
        AiFeatureSettings settings = AiFeatureSettings.getInstance();
        if (!settings.isCodeCompletionEnabled()) {
            return Optional.empty();
        }
        if (triggerMode == AiCompletionTriggerMode.AUTO) {
            if (!settings.isAutoCompletionEnabled() || shouldSkipAutoRequest()) {
                return Optional.empty();
            }
        }
        if (triggerMode == AiCompletionTriggerMode.MANUAL && shouldSkipManualRequest()) {
            return Optional.empty();
        }

        String inFlightKey = completionKey(project, editor);
        if (inFlightCompletionKeys.putIfAbsent(inFlightKey, System.currentTimeMillis()) != null) {
            return Optional.empty();
        }

        long documentStamp = editor.getDocument().getModificationStamp();
        int caretOffset = editor.getCaretModel().getOffset();
        try {
            AiModelProfile profile = settings.getActiveCompletionProfile();
            if (profile == null || profile.getModel().isBlank()) {
                return Optional.empty();
            }
            String apiKey = settings.getApiKey(profile.getId());
            if (apiKey.isBlank()) {
                return Optional.empty();
            }

            AiCompletionLengthLevel lengthLevel = settings.getCompletionLengthLevel(triggerMode);
            AiCompletionContextBuilder.Context context = AiCompletionContextBuilder.build(project, editor, triggerMode, lengthLevel);
            AiCompletionRequest request = new AiCompletionRequest(
                profile,
                apiKey,
                context.systemPrompt(),
                context.userPrompt(),
                lengthLevel,
                settings.getCompletionMaxTokens(triggerMode)
            );
            String completion = createClient(profile.getFormat()).complete(request);
            if (completion == null || completion.isBlank()) {
                return Optional.empty();
            }
            if (editor.getDocument().getModificationStamp() != documentStamp
                || editor.getCaretModel().getOffset() != caretOffset) {
                return Optional.empty();
            }
            return Optional.of(completion);
        } finally {
            inFlightCompletionKeys.remove(inFlightKey);
        }
    }

    public boolean isCompletionInProgress(Project project, Editor editor) {
        if (project == null || editor == null) {
            return false;
        }
        return inFlightCompletionKeys.containsKey(completionKey(project, editor));
    }

    public Optional<String> generateText(String systemPrompt, String userPrompt, AiCompletionLengthLevel lengthLevel)
        throws IOException, InterruptedException {
        AiFeatureSettings settings = AiFeatureSettings.getInstance();
        AiModelProfile profile = settings.getActiveCompletionProfile();
        if (profile == null || profile.getModel().isBlank()) {
            return Optional.empty();
        }
        String apiKey = settings.getApiKey(profile.getId());
        if (apiKey.isBlank()) {
            return Optional.empty();
        }

        AiCompletionRequest request = new AiCompletionRequest(
            profile,
            apiKey,
            systemPrompt,
            userPrompt,
            lengthLevel,
            lengthLevel.getMaxTokens()
        );
        String text = createClient(profile.getFormat()).complete(request);
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(text);
    }

    private synchronized boolean shouldSkipAutoRequest() {
        long now = System.currentTimeMillis();
        if (now - lastAutoCompletionRequestMs < AUTO_COMPLETION_COOLDOWN_MS) {
            return true;
        }
        lastAutoCompletionRequestMs = now;
        return false;
    }

    private synchronized boolean shouldSkipManualRequest() {
        long now = System.currentTimeMillis();
        if (now - lastManualCompletionRequestMs < MANUAL_COMPLETION_COOLDOWN_MS) {
            return true;
        }
        lastManualCompletionRequestMs = now;
        return false;
    }

    private String completionKey(Project project, Editor editor) {
        String projectKey = project == null ? "" : project.getLocationHash();
        return projectKey
            + ":"
            + System.identityHashCode(editor.getDocument());
    }

    private AiCompletionClient createClient(AiModelFormat format) {
        return switch (format) {
            case OPENAI_RESPONSES -> new OpenAiResponsesCompletionClient();
            case OPENAI_CHAT_COMPLETIONS -> new OpenAiChatCompletionClient();
            case ANTHROPIC_MESSAGES -> new AnthropicMessagesCompletionClient();
        };
    }
}
