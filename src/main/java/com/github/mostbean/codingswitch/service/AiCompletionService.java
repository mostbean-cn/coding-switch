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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@Service(Service.Level.APP)
public final class AiCompletionService {

    private static final int SUFFIX_ECHO_GUARD_CHARS = 120;

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
            return Optional.ofNullable(normalizeCompletion(context.request(), cached.get()))
                .filter(value -> !value.isBlank());
        }

        try {
            String completion = createClient(context.profile().getFormat()).complete(context.request());
            completion = normalizeCompletion(context.request(), completion);
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
            String completion = normalizeCompletion(context.request(), cached.get());
            if (completion != null && !completion.isBlank()) {
                onDelta.accept(completion);
            }
            inFlightCompletionKeys.remove(context.inFlightKey());
            return completion != null && !completion.isBlank();
        }

        StringBuilder fullCompletion = new StringBuilder();
        AtomicBoolean hasText = new AtomicBoolean(false);
        CompletionDeltaFilter deltaFilter = createDeltaFilter(context.request());
        try {
            AiCompletionClient client = createClient(context.profile().getFormat());
            try {
                client.streamComplete(context.request(), delta -> {
                    if (delta == null || delta.isEmpty() || !isStillValid(editor, context.snapshot())) {
                        return;
                    }
                    String visibleDelta = deltaFilter == null ? delta : deltaFilter.append(delta);
                    if (visibleDelta.isEmpty()) {
                        return;
                    }
                    hasText.set(true);
                    fullCompletion.append(visibleDelta);
                    onDelta.accept(visibleDelta);
                });
                if (deltaFilter != null) {
                    String remaining = deltaFilter.finish();
                    if (!remaining.isEmpty() && isStillValid(editor, context.snapshot())) {
                        hasText.set(true);
                        fullCompletion.append(remaining);
                        onDelta.accept(remaining);
                    }
                }
            } catch (IOException ex) {
                if (hasText.get()) {
                    throw ex;
                }
                String completion = client.complete(context.request());
                completion = normalizeCompletion(context.request(), completion);
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

        String inFlightKey = PlatformReadAccess.compute(() -> completionKey(project, editor));
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
            CompletionSnapshot snapshot = PlatformReadAccess.compute(() -> new CompletionSnapshot(
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
                lengthLevel.getMaxTokens(),
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
        return PlatformReadAccess.compute(() -> inFlightCompletionKeys.containsKey(completionKey(project, editor)));
    }

    public Optional<String> generateText(String systemPrompt, String userPrompt, AiCompletionLengthLevel lengthLevel)
        throws IOException, InterruptedException {
        AiFeatureSettings settings = AiFeatureSettings.getInstance();
        return generateText(systemPrompt, userPrompt, lengthLevel, settings.getActiveCompletionProfile());
    }

    public Optional<String> generateGitCommitText(String systemPrompt, String userPrompt, AiCompletionLengthLevel lengthLevel)
        throws IOException, InterruptedException {
        return generateText(
            systemPrompt,
            userPrompt,
            lengthLevel,
            BuiltInGitCommitModel.profile(),
            BuiltInGitCommitModel.apiKey()
        );
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
        if (profile.getFormat() == AiModelFormat.FIM_COMPLETIONS
            || profile.getFormat() == AiModelFormat.FIM_CHAT_COMPLETIONS) {
            return Optional.empty();
        }
        String apiKey = settings.getApiKey(profile.getId());
        if (apiKey.isBlank()) {
            return Optional.empty();
        }
        return generateText(systemPrompt, userPrompt, lengthLevel, profile, apiKey);
    }

    private Optional<String> generateText(
        String systemPrompt,
        String userPrompt,
        AiCompletionLengthLevel lengthLevel,
        AiModelProfile profile,
        String apiKey
    ) throws IOException, InterruptedException {
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

    private String normalizeCompletion(AiCompletionRequest request, String completion) {
        if (completion == null) {
            return null;
        }
        if (request.lengthLevel() == AiCompletionLengthLevel.SINGLE_LINE) {
            return firstEffectiveLine(completion);
        }
        return trimCompletionTail(trimSuffixEcho(request, completion));
    }

    private String firstEffectiveLine(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String normalized = text.replace("\r\n", "\n").replace("\r", "\n");
        for (String line : normalized.split("\n", -1)) {
            if (!line.isBlank()) {
                return line.stripTrailing();
            }
        }
        return "";
    }

    private CompletionDeltaFilter createDeltaFilter(AiCompletionRequest request) {
        if (request.lengthLevel() == AiCompletionLengthLevel.SINGLE_LINE) {
            return new SingleLineDeltaFilter();
        }
        return new SuffixEchoDeltaFilter(request);
    }

    private String trimSuffixEcho(AiCompletionRequest request, String completion) {
        if (completion == null || completion.isEmpty()) {
            return completion;
        }
        String suffixLine = firstEffectiveLine(request.fimSuffix()).strip();
        if (suffixLine.isEmpty()) {
            return completion;
        }
        int echoStart = suffixEchoStart(completion, suffixLine);
        if (echoStart < 0) {
            return completion;
        }
        return completion.substring(0, echoStart).stripTrailing();
    }

    private String trimCompletionTail(String completion) {
        return completion == null ? null : completion.stripTrailing();
    }

    private int suffixEchoStart(String completion, String suffixLine) {
        int exactIndex = completion.indexOf(suffixLine);
        if (exactIndex >= 0) {
            return exactIndex;
        }

        String normalizedSuffixLine = removeWhitespace(suffixLine);
        if (normalizedSuffixLine.isEmpty()) {
            return -1;
        }

        int lineStart = 0;
        while (lineStart < completion.length()) {
            int lineEnd = completion.indexOf('\n', lineStart);
            if (lineEnd < 0) {
                lineEnd = completion.length();
            }
            String line = completion.substring(lineStart, lineEnd).strip();
            if (removeWhitespace(line).equals(normalizedSuffixLine)) {
                return lineStart;
            }
            lineStart = lineEnd + 1;
        }
        return -1;
    }

    private String removeWhitespace(String value) {
        StringBuilder result = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (!Character.isWhitespace(ch)) {
                result.append(ch);
            }
        }
        return result.toString();
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
        return PlatformReadAccess.compute(() ->
            editor.getDocument().getModificationStamp() == snapshot.documentStamp()
                && editor.getCaretModel().getOffset() == snapshot.caretOffset()
        );
    }

    private AiCompletionClient createClient(AiModelFormat format) {
        return switch (format) {
            case OPENAI_RESPONSES -> new OpenAiResponsesCompletionClient();
            case OPENAI_CHAT_COMPLETIONS -> new OpenAiChatCompletionClient();
            case FIM_COMPLETIONS -> new FimCompletionClient();
            case FIM_CHAT_COMPLETIONS -> new FimChatCompletionClient();
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

    private interface CompletionDeltaFilter {
        String append(String delta);

        String finish();
    }

    private final class SingleLineDeltaFilter implements CompletionDeltaFilter {
        private final StringBuilder raw = new StringBuilder();
        private int emittedLength;

        @Override
        public String append(String delta) {
            raw.append(delta);
            String visible = firstEffectiveLine(raw.toString());
            if (visible.length() <= emittedLength) {
                return "";
            }
            String next = visible.substring(emittedLength);
            emittedLength = visible.length();
            return next;
        }

        @Override
        public String finish() {
            return "";
        }
    }

    private final class SuffixEchoDeltaFilter implements CompletionDeltaFilter {
        private final AiCompletionRequest request;
        private final StringBuilder raw = new StringBuilder();
        private int emittedLength;

        private SuffixEchoDeltaFilter(AiCompletionRequest request) {
            this.request = request;
        }

        @Override
        public String append(String delta) {
            raw.append(delta);
            String visible = trimSuffixEcho(request, raw.toString());
            boolean echoDetected = visible.length() < raw.length();
            int flushUntil = echoDetected
                ? visible.length()
                : Math.max(0, visible.length() - SUFFIX_ECHO_GUARD_CHARS);
            return emitUntil(visible, flushUntil);
        }

        @Override
        public String finish() {
            String visible = trimCompletionTail(trimSuffixEcho(request, raw.toString()));
            return emitUntil(visible, visible.length());
        }

        private String emitUntil(String visible, int flushUntil) {
            if (flushUntil <= emittedLength) {
                return "";
            }
            String next = visible.substring(emittedLength, flushUntil);
            emittedLength = flushUntil;
            return next;
        }
    }
}
