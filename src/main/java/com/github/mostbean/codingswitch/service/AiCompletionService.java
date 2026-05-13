package com.github.mostbean.codingswitch.service;

import com.github.mostbean.codingswitch.model.AiCompletionRequest;
import com.github.mostbean.codingswitch.model.AiCompletionLengthLevel;
import com.github.mostbean.codingswitch.model.AiCompletionTriggerMode;
import com.github.mostbean.codingswitch.model.AiModelFormat;
import com.github.mostbean.codingswitch.model.AiModelProfile;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@Service(Service.Level.APP)
public final class AiCompletionService {

    private final Map<String, Long> inFlightCompletionKeys = new ConcurrentHashMap<>();
    private long lastManualCompletionRequestMs = 0L;

    public static AiCompletionService getInstance() {
        return ApplicationManager.getApplication().getService(AiCompletionService.class);
    }

    public Optional<String> complete(Project project, Editor editor, AiCompletionTriggerMode triggerMode)
        throws IOException, InterruptedException {
        CompletionRequestContext context = prepareCompletionRequest(project, editor, triggerMode);
        if (context == null) {
            return Optional.empty();
        }

        AiCompletionCache cache = AiCompletionCache.getInstance();
        String filePath = context.snapshot().context().filePath();
        Optional<String> cached = cache.get(filePath, context.snapshot().caretOffset(), context.snapshot().documentStamp());
        if (cached.isPresent()) {
            inFlightCompletionKeys.remove(context.inFlightKey());
            return cached;
        }

        try {
            String completion = createClient(context.profile().getFormat()).complete(context.request());
            if (completion == null || completion.isBlank()) {
                return Optional.empty();
            }
            if (!isStillValid(editor, context.snapshot())) {
                return Optional.empty();
            }
            cache.put(filePath, context.snapshot().caretOffset(), context.snapshot().documentStamp(), completion);
            return Optional.of(completion);
        } finally {
            inFlightCompletionKeys.remove(context.inFlightKey());
        }
    }

    public boolean streamComplete(
        Project project,
        Editor editor,
        AiCompletionTriggerMode triggerMode,
        Consumer<String> onDelta
    ) throws IOException, InterruptedException {
        CompletionRequestContext context = prepareCompletionRequest(project, editor, triggerMode);
        if (context == null) {
            return false;
        }

        AiCompletionCache cache = AiCompletionCache.getInstance();
        String filePath = context.snapshot().context().filePath();
        Optional<String> cached = cache.get(filePath, context.snapshot().caretOffset(), context.snapshot().documentStamp());
        if (cached.isPresent()) {
            onDelta.accept(cached.get());
            inFlightCompletionKeys.remove(context.inFlightKey());
            return true;
        }

        StringBuilder fullCompletion = new StringBuilder();
        AtomicBoolean hasText = new AtomicBoolean(false);
        try {
            AiCompletionClient client = createClient(context.profile().getFormat());
            try {
                client.streamComplete(context.request(), delta -> {
                    if (delta == null || delta.isEmpty() || !isStillValid(editor, context.snapshot())) {
                        return;
                    }
                    hasText.set(true);
                    fullCompletion.append(delta);
                    onDelta.accept(delta);
                });
            } catch (IOException ex) {
                if (hasText.get()) {
                    throw ex;
                }
                String completion = client.complete(context.request());
                if (completion != null && !completion.isBlank() && isStillValid(editor, context.snapshot())) {
                    hasText.set(true);
                    fullCompletion.append(completion);
                    onDelta.accept(completion);
                }
            }
            if (hasText.get()) {
                cache.put(filePath, context.snapshot().caretOffset(), context.snapshot().documentStamp(), fullCompletion.toString());
            }
            return hasText.get();
        } finally {
            inFlightCompletionKeys.remove(context.inFlightKey());
        }
    }

    private CompletionRequestContext prepareCompletionRequest(
        Project project,
        Editor editor,
        AiCompletionTriggerMode triggerMode
    ) {
        AiFeatureSettings settings = AiFeatureSettings.getInstance();
        if (!settings.isCodeCompletionEnabled()) {
            return null;
        }
        if (triggerMode == AiCompletionTriggerMode.AUTO && !settings.isAutoCompletionEnabled()) {
            return null;
        }
        if (!AiCompletionEditorGuard.isEligible(project, editor)) {
            return null;
        }
        if (triggerMode == AiCompletionTriggerMode.MANUAL && shouldSkipManualRequest()) {
            return null;
        }

        String inFlightKey = ReadAction.compute(() -> completionKey(project, editor));
        if (inFlightCompletionKeys.putIfAbsent(inFlightKey, System.currentTimeMillis()) != null) {
            return null;
        }

        try {
            AiModelProfile profile = settings.getActiveCompletionProfile();
            if (profile == null || profile.getModel().isBlank()) {
                inFlightCompletionKeys.remove(inFlightKey);
                return null;
            }
            String apiKey = settings.getApiKey(profile.getId());
            if (apiKey.isBlank()) {
                inFlightCompletionKeys.remove(inFlightKey);
                return null;
            }

            AiCompletionLengthLevel lengthLevel = settings.getCompletionLengthLevel(triggerMode);
            CompletionSnapshot snapshot = ReadAction.compute(() -> new CompletionSnapshot(
                editor.getDocument().getModificationStamp(),
                editor.getCaretModel().getOffset(),
                AiCompletionContextBuilder.build(project, editor, triggerMode, lengthLevel)
            ));
            AiCompletionRequest request = new AiCompletionRequest(
                profile,
                apiKey,
                snapshot.context().systemPrompt(),
                snapshot.context().userPrompt(),
                lengthLevel,
                settings.getCompletionMaxTokens(triggerMode),
                snapshot.context().fimPrefix(),
                snapshot.context().fimSuffix()
            );
            return new CompletionRequestContext(inFlightKey, profile, request, snapshot);
        } catch (RuntimeException ex) {
            inFlightCompletionKeys.remove(inFlightKey);
            throw ex;
        }
    }

    public boolean isCompletionInProgress(Project project, Editor editor) {
        if (project == null || editor == null) {
            return false;
        }
        return ReadAction.compute(() -> inFlightCompletionKeys.containsKey(completionKey(project, editor)));
    }

    public Optional<String> generateText(String systemPrompt, String userPrompt, AiCompletionLengthLevel lengthLevel)
        throws IOException, InterruptedException {
        AiFeatureSettings settings = AiFeatureSettings.getInstance();
        return generateText(systemPrompt, userPrompt, lengthLevel, settings.getActiveCompletionProfile());
    }

    public Optional<String> generateGitCommitText(String systemPrompt, String userPrompt, AiCompletionLengthLevel lengthLevel)
        throws IOException, InterruptedException {
        AiFeatureSettings settings = AiFeatureSettings.getInstance();
        return generateText(systemPrompt, userPrompt, lengthLevel, settings.getActiveGitCommitProfile());
    }

    private Optional<String> generateText(
        String systemPrompt,
        String userPrompt,
        AiCompletionLengthLevel lengthLevel,
        AiModelProfile profile
    ) throws IOException, InterruptedException {
        AiFeatureSettings settings = AiFeatureSettings.getInstance();
        if (profile == null || profile.getModel().isBlank()) {
            return Optional.empty();
        }
        if (profile.getFormat() == AiModelFormat.DEEPSEEK_FIM_COMPLETIONS) {
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
            lengthLevel.getMaxTokens(),
            "",
            ""
        );
        String text = createClient(profile.getFormat()).complete(request);
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(text);
    }

    private synchronized boolean shouldSkipManualRequest() {
        long now = System.currentTimeMillis();
        long cooldown = AiFeatureSettings.getInstance().getTimingConfig().getManualCooldownMs();
        if (now - lastManualCompletionRequestMs < cooldown) {
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

    private boolean isStillValid(Editor editor, CompletionSnapshot snapshot) {
        return ReadAction.compute(() ->
            editor.getDocument().getModificationStamp() == snapshot.documentStamp()
                && editor.getCaretModel().getOffset() == snapshot.caretOffset()
        );
    }

    private AiCompletionClient createClient(AiModelFormat format) {
        return switch (format) {
            case OPENAI_RESPONSES -> new OpenAiResponsesCompletionClient();
            case OPENAI_CHAT_COMPLETIONS -> new OpenAiChatCompletionClient();
            case DEEPSEEK_FIM_COMPLETIONS -> new DeepSeekFimCompletionClient();
            case ANTHROPIC_MESSAGES -> new AnthropicMessagesCompletionClient();
        };
    }

    private record CompletionSnapshot(
        long documentStamp,
        int caretOffset,
        AiCompletionContextBuilder.Context context
    ) {
    }

    private record CompletionRequestContext(
        String inFlightKey,
        AiModelProfile profile,
        AiCompletionRequest request,
        CompletionSnapshot snapshot
    ) {
    }
}
