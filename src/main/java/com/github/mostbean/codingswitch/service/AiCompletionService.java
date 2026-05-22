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

    public enum CompletionStatus {
        SUCCESS,
        NO_RESULT,
        NEGATIVE_CACHED,
        COOLDOWN,
        IN_FLIGHT,
        STALE_CONTEXT,
        CONFIG_UNAVAILABLE
    }

    public record CompletionResult(CompletionStatus status, String message) {
        public static CompletionResult success() {
            return new CompletionResult(CompletionStatus.SUCCESS, "");
        }

        public static CompletionResult skipped(CompletionStatus status) {
            return new CompletionResult(status, "");
        }

        public static CompletionResult skipped(CompletionStatus status, String message) {
            return new CompletionResult(status, message == null ? "" : message);
        }

        public static CompletionResult unavailable(String message) {
            return new CompletionResult(CompletionStatus.CONFIG_UNAVAILABLE, message == null ? "" : message);
        }

        public boolean isSuccess() {
            return status == CompletionStatus.SUCCESS;
        }
    }

    public Optional<String> complete(Project project, Editor editor, AiCompletionTriggerMode triggerMode)
        throws IOException, InterruptedException {
        CompletionPreparation preparation = prepareCompletionRequest(project, editor, triggerMode);
        CompletionRequestContext context = preparation.context();
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
        Optional<String> contextCached = cache.getContext(context.request());
        if (contextCached.isPresent()) {
            String completion = normalizeCompletion(context.request(), contextCached.get());
            if (completion != null && !completion.isBlank()) {
                cache.put(filePath, context.snapshot().caretOffset(), context.snapshot().documentStamp(), completion);
                inFlightCompletionKeys.remove(context.inFlightKey());
                return Optional.of(completion);
            }
        }
        if (cache.isNegativeCached(context.request())) {
            inFlightCompletionKeys.remove(context.inFlightKey());
            return Optional.empty();
        }

        try {
            String completion = createClient(context.profile().getFormat()).complete(context.request());
            completion = normalizeCompletion(context.request(), completion);
            if (completion == null || completion.isBlank()) {
                cache.putNegative(context.request());
                return Optional.empty();
            }
            if (!isStillValid(editor, context.snapshot())) {
                return Optional.empty();
            }
            cache.put(filePath, context.snapshot().caretOffset(), context.snapshot().documentStamp(), completion);
            cache.putContext(context.request(), completion);
            return Optional.of(completion);
        } finally {
            inFlightCompletionKeys.remove(context.inFlightKey());
        }
    }

    public CompletionResult streamComplete(
        Project project,
        Editor editor,
        AiCompletionTriggerMode triggerMode,
        Consumer<String> onDelta
    ) throws IOException, InterruptedException {
        CompletionPreparation preparation = prepareCompletionRequest(project, editor, triggerMode);
        CompletionRequestContext context = preparation.context();
        if (context == null) {
            return CompletionResult.skipped(preparation.status(), preparation.message());
        }

        AiCompletionCache cache = AiCompletionCache.getInstance();
        String filePath = context.snapshot().context().filePath();
        Optional<String> cached = cache.get(filePath, context.snapshot().caretOffset(), context.snapshot().documentStamp());
        if (cached.isPresent()) {
            String completion = normalizeCompletion(context.request(), cached.get());
            if (completion != null && !completion.isBlank()) {
                onDelta.accept(completion);
                inFlightCompletionKeys.remove(context.inFlightKey());
                return CompletionResult.success();
            }
            inFlightCompletionKeys.remove(context.inFlightKey());
            return CompletionResult.skipped(CompletionStatus.NO_RESULT);
        }
        Optional<String> contextCached = cache.getContext(context.request());
        if (contextCached.isPresent()) {
            String completion = normalizeCompletion(context.request(), contextCached.get());
            if (completion != null && !completion.isBlank()) {
                cache.put(filePath, context.snapshot().caretOffset(), context.snapshot().documentStamp(), completion);
                onDelta.accept(completion);
                inFlightCompletionKeys.remove(context.inFlightKey());
                return CompletionResult.success();
            }
        }
        if (triggerMode == AiCompletionTriggerMode.AUTO && cache.isNegativeCached(context.request())) {
            inFlightCompletionKeys.remove(context.inFlightKey());
            return CompletionResult.skipped(CompletionStatus.NEGATIVE_CACHED);
        }

        StringBuilder fullCompletion = new StringBuilder();
        AtomicBoolean hasText = new AtomicBoolean(false);
        AtomicBoolean staleContext = new AtomicBoolean(false);
        CompletionDeltaFilter deltaFilter = createDeltaFilter(context.request());
        try {
            AiCompletionClient client = createClient(context.profile().getFormat());
            try {
                client.streamComplete(context.request(), delta -> {
                    if (delta == null || delta.isEmpty()) {
                        return;
                    }
                    if (!isStillValid(editor, context.snapshot())) {
                        staleContext.set(true);
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
                    } else if (!remaining.isEmpty()) {
                        staleContext.set(true);
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
                } else if (completion != null && !completion.isBlank()) {
                    staleContext.set(true);
                }
            }
            if (hasText.get()) {
                String completion = fullCompletion.toString();
                cache.put(filePath, context.snapshot().caretOffset(), context.snapshot().documentStamp(), completion);
                cache.putContext(context.request(), completion);
                return CompletionResult.success();
            }
            if (staleContext.get()) {
                return CompletionResult.skipped(CompletionStatus.STALE_CONTEXT);
            }
            if (triggerMode == AiCompletionTriggerMode.AUTO) {
                cache.putNegative(context.request());
            }
            return CompletionResult.skipped(CompletionStatus.NO_RESULT);
        } finally {
            inFlightCompletionKeys.remove(context.inFlightKey());
        }
    }

    private CompletionPreparation prepareCompletionRequest(
        Project project,
        Editor editor,
        AiCompletionTriggerMode triggerMode
    ) {
        AiFeatureSettings settings = AiFeatureSettings.getInstance();
        if (!settings.isCodeCompletionEnabled()) {
            return CompletionPreparation.unavailable("代码补全功能未启用");
        }
        if (triggerMode == AiCompletionTriggerMode.AUTO && !settings.isAutoCompletionEnabled()) {
            return CompletionPreparation.skipped(CompletionStatus.CONFIG_UNAVAILABLE);
        }
        if (!AiCompletionEditorGuard.isEligible(project, editor)) {
            return CompletionPreparation.skipped(CompletionStatus.STALE_CONTEXT);
        }
        if (triggerMode == AiCompletionTriggerMode.MANUAL && shouldSkipManualRequest()) {
            return CompletionPreparation.skipped(CompletionStatus.COOLDOWN);
        }

        String inFlightKey = PlatformReadAccess.compute(() -> completionKey(project, editor, triggerMode));
        if (inFlightCompletionKeys.putIfAbsent(inFlightKey, System.currentTimeMillis()) != null) {
            return CompletionPreparation.skipped(CompletionStatus.IN_FLIGHT);
        }

        try {
            AiModelProfile profile = settings.getActiveCompletionProfile();
            if (profile == null || profile.getModel().isBlank()) {
                inFlightCompletionKeys.remove(inFlightKey);
                return CompletionPreparation.unavailable("请先配置补全模型");
            }
            String apiKey = settings.getApiKey(profile.getId());
            if (apiKey.isBlank()) {
                inFlightCompletionKeys.remove(inFlightKey);
                return CompletionPreparation.unavailable("请先配置补全模型 API Key");
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
            return CompletionPreparation.ready(new CompletionRequestContext(inFlightKey, profile, request, snapshot));
        } catch (RuntimeException ex) {
            inFlightCompletionKeys.remove(inFlightKey);
            throw ex;
        }
    }

    public boolean isCompletionInProgress(Project project, Editor editor) {
        if (project == null || editor == null) {
            return false;
        }
        return PlatformReadAccess.compute(() ->
            inFlightCompletionKeys.containsKey(completionKey(project, editor, AiCompletionTriggerMode.AUTO))
                || inFlightCompletionKeys.containsKey(completionKey(project, editor, AiCompletionTriggerMode.MANUAL))
        );
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

    public Optional<String> streamGitCommitText(
        String systemPrompt,
        String userPrompt,
        AiCompletionLengthLevel lengthLevel,
        Consumer<String> onDelta
    ) throws IOException, InterruptedException {
        AiFeatureSettings settings = AiFeatureSettings.getInstance();
        return streamText(systemPrompt, userPrompt, lengthLevel, settings.getActiveGitCommitProfile(), onDelta);
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
        AiCompletionRequest request = createTextRequest(systemPrompt, userPrompt, lengthLevel, profile, apiKey);
        String text = createClient(profile.getFormat()).complete(request);
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(text);
    }

    private Optional<String> streamText(
        String systemPrompt,
        String userPrompt,
        AiCompletionLengthLevel lengthLevel,
        AiModelProfile profile,
        Consumer<String> onDelta
    ) throws IOException, InterruptedException {
        AiFeatureSettings settings = AiFeatureSettings.getInstance();
        if (profile == null || profile.getModel().isBlank()) {
            return Optional.empty();
        }
        String apiKey = settings.getApiKey(profile.getId());
        if (apiKey.isBlank()) {
            return Optional.empty();
        }
        AiCompletionRequest request = createTextRequest(systemPrompt, userPrompt, lengthLevel, profile, apiKey);
        StringBuilder text = new StringBuilder();
        createClient(profile.getFormat()).streamComplete(request, delta -> {
            if (delta == null || delta.isEmpty()) {
                return;
            }
            text.append(delta);
            if (onDelta != null) {
                onDelta.accept(delta);
            }
        });
        return text.isEmpty() ? Optional.empty() : Optional.of(text.toString());
    }

    private AiCompletionRequest createTextRequest(
        String systemPrompt,
        String userPrompt,
        AiCompletionLengthLevel lengthLevel,
        AiModelProfile profile,
        String apiKey
    ) {
        boolean fimCompletion = profile.getFormat() == AiModelFormat.FIM_COMPLETIONS;
        return new AiCompletionRequest(
            profile,
            apiKey,
            systemPrompt,
            fimCompletion ? "" : userPrompt,
            lengthLevel,
            lengthLevel.getMaxTokens(),
            fimCompletion ? userPrompt : "",
            ""
        );
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

    private String completionKey(Project project, Editor editor, AiCompletionTriggerMode triggerMode) {
        String projectKey = project == null ? "" : project.getLocationHash();
        return projectKey
            + ":"
            + System.identityHashCode(editor.getDocument())
            + ":"
            + triggerMode.name().toLowerCase();
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

    private record CompletionPreparation(
        CompletionStatus status,
        String message,
        CompletionRequestContext context
    ) {
        private static CompletionPreparation ready(CompletionRequestContext context) {
            return new CompletionPreparation(CompletionStatus.SUCCESS, "", context);
        }

        private static CompletionPreparation skipped(CompletionStatus status) {
            return new CompletionPreparation(status, "", null);
        }

        private static CompletionPreparation unavailable(String message) {
            return new CompletionPreparation(CompletionStatus.CONFIG_UNAVAILABLE, message == null ? "" : message, null);
        }
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
