package com.github.mostbean.codingswitch.service;

import com.github.mostbean.codingswitch.model.AiCompletionTriggerMode;
import com.github.mostbean.codingswitch.model.AiModelProfile;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorCustomElementRenderer;
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.editor.LogicalPosition;
import com.intellij.openapi.editor.event.CaretEvent;
import com.intellij.openapi.editor.event.CaretListener;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.editor.colors.EditorFontType;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Key;
import com.intellij.ui.JBColor;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Service(Service.Level.APP)
public final class AiInlineCompletionService implements Disposable {

    private static final Key<InlineSession> SESSION_KEY = Key.create("coding.switch.ai.inline.session");
    private static final Key<Long> REQUEST_ID_KEY = Key.create("coding.switch.ai.inline.request.id");
    private static final Color GHOST_FOREGROUND = new JBColor(new Color(0x8A8A8A), new Color(0x6F737A));

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Coding Switch Inline Completion");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicLong requestIds = new AtomicLong();

    public static AiInlineCompletionService getInstance() {
        return ApplicationManager.getApplication().getService(AiInlineCompletionService.class);
    }

    public void scheduleAuto(Project project, Editor editor) {
        if (project == null || editor == null || hasActiveCompletion(editor)) {
            return;
        }
        long requestId = requestIds.incrementAndGet();
        editor.putUserData(REQUEST_ID_KEY, requestId);
        long delay = AiFeatureSettings.getInstance().getTimingConfig().getDebounceDelayMs();
        scheduler.schedule(
            () -> ApplicationManager.getApplication().invokeLater(() -> {
                Long current = editor.getUserData(REQUEST_ID_KEY);
                if (current != null && current == requestId) {
                    request(project, editor, AiCompletionTriggerMode.AUTO, requestId);
                }
            }),
            delay,
            TimeUnit.MILLISECONDS
        );
    }

    public void requestManual(Project project, Editor editor) {
        if (project == null || editor == null) {
            return;
        }
        hide(editor);
        long requestId = requestIds.incrementAndGet();
        editor.putUserData(REQUEST_ID_KEY, requestId);
        request(project, editor, AiCompletionTriggerMode.MANUAL, requestId);
    }

    public void hide(Editor editor) {
        if (editor == null) {
            return;
        }
        InlineSession session = editor.getUserData(SESSION_KEY);
        if (session == null) {
            return;
        }
        session.dispose(editor);
        editor.putUserData(SESSION_KEY, null);
    }

    public boolean hasActiveCompletion(Editor editor) {
        InlineSession session = editor == null ? null : editor.getUserData(SESSION_KEY);
        return session != null && !session.remainingText.isBlank();
    }

    public boolean acceptAll(Project project, Editor editor) {
        InlineSession session = validSession(editor);
        if (project == null || session == null) {
            return false;
        }
        session.detachInvalidationListeners(editor);
        insertText(project, editor, session, session.remainingText);
        hide(editor);
        return true;
    }

    public boolean acceptLine(Project project, Editor editor) {
        InlineSession session = validSession(editor);
        if (project == null || session == null) {
            return false;
        }
        String chunk = nextLineChunk(session.remainingText);
        session.detachInvalidationListeners(editor);
        insertText(project, editor, session, chunk);
        session.consumeVisiblePrefix(chunk);
        session.offset += chunk.length();
        session.documentStamp = editor.getDocument().getModificationStamp();
        session.disposeInlays();
        if (session.remainingText.isBlank()) {
            editor.putUserData(SESSION_KEY, null);
        } else {
            renderSession(editor, session);
            session.attachInvalidationListeners(editor);
        }
        return true;
    }

    private void request(Project project, Editor editor, AiCompletionTriggerMode triggerMode, long requestId) {
        if (AiCompletionService.getInstance().isCompletionInProgress(project, editor)) {
            if (triggerMode == AiCompletionTriggerMode.AUTO) {
                scheduleInFlightRetry(project, editor, requestId);
            }
            return;
        }
        String unavailableReason = unavailableReason(triggerMode);
        if (unavailableReason != null) {
            notifyManualFailure(project, triggerMode, unavailableReason);
            return;
        }
        int offset = editor.getCaretModel().getOffset();
        long documentStamp = editor.getDocument().getModificationStamp();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            boolean received;
            StreamAccumulator accumulator = new StreamAccumulator();
            try {
                received = AiCompletionService.getInstance().streamComplete(project, editor, triggerMode, delta ->
                    enqueueDelta(editor, requestId, offset, documentStamp, accumulator, delta)
                );
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                notifyManualFailure(project, triggerMode, "生成补全被中断");
                return;
            } catch (Exception ignored) {
                notifyManualFailure(project, triggerMode, "生成补全失败: " + ignored.getMessage());
                return;
            }
            if (!received) {
                notifyManualFailure(project, triggerMode, "未生成代码补全，请检查模型配置或当前上下文。");
            } else {
                flushDelta(editor, requestId, offset, documentStamp, accumulator);
            }
        });
    }

    private void scheduleInFlightRetry(Project project, Editor editor, long requestId) {
        long delay = AiFeatureSettings.getInstance().getTimingConfig().getInFlightRetryDelayMs();
        scheduler.schedule(
            () -> ApplicationManager.getApplication().invokeLater(() -> {
                Long current = editor.getUserData(REQUEST_ID_KEY);
                if (current != null && current == requestId && !hasActiveCompletion(editor)) {
                    request(project, editor, AiCompletionTriggerMode.AUTO, requestId);
                }
            }),
            delay,
            TimeUnit.MILLISECONDS
        );
    }

    private String unavailableReason(AiCompletionTriggerMode triggerMode) {
        AiFeatureSettings settings = AiFeatureSettings.getInstance();
        if (!settings.isCodeCompletionEnabled()) {
            return "代码补全功能未启用";
        }
        if (triggerMode == AiCompletionTriggerMode.AUTO && !settings.isAutoCompletionEnabled()) {
            return "自动补全未启用";
        }
        AiModelProfile profile = settings.getActiveCompletionProfile();
        if (profile == null || profile.getModel().isBlank()) {
            return "请先配置补全模型";
        }
        if (settings.getApiKey(profile.getId()).isBlank()) {
            return "请先配置补全模型 API Key";
        }
        return null;
    }

    private void notifyManualFailure(Project project, AiCompletionTriggerMode triggerMode, String message) {
        if (triggerMode != AiCompletionTriggerMode.MANUAL || project == null) {
            return;
        }
        ApplicationManager.getApplication().invokeLater(() ->
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Coding Switch")
                .createNotification(message, NotificationType.WARNING)
                .notify(project)
        );
    }

    private void appendDelta(Editor editor, long requestId, int offset, long documentStamp, String delta) {
        Long current = editor.getUserData(REQUEST_ID_KEY);
        if (current == null || current != requestId) {
            return;
        }
        if (editor.getDocument().getModificationStamp() != documentStamp
            || editor.getCaretModel().getOffset() != offset) {
            return;
        }
        String text = normalizeDelta(delta);
        if (text.isEmpty()) {
            return;
        }

        InlineSession session = editor.getUserData(SESSION_KEY);
        if (session == null) {
            session = new InlineSession(offset, text, documentStamp);
            editor.putUserData(SESSION_KEY, session);
            session.attachInvalidationListeners(editor);
        } else {
            session.appendText(text);
        }
        if (session.remainingText.isEmpty()) {
            return;
        }
        session.disposeInlays();
        renderSession(editor, session);
    }

    private void enqueueDelta(
        Editor editor,
        long requestId,
        int offset,
        long documentStamp,
        StreamAccumulator accumulator,
        String delta
    ) {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        synchronized (accumulator) {
            accumulator.buffer.append(delta);
            if (accumulator.flushScheduled) {
                return;
            }
            accumulator.flushScheduled = true;
        }
        long throttle = AiFeatureSettings.getInstance().getTimingConfig().getStreamRenderThrottleMs();
        scheduler.schedule(
            () -> flushDelta(editor, requestId, offset, documentStamp, accumulator),
            throttle,
            TimeUnit.MILLISECONDS
        );
    }

    private void flushDelta(
        Editor editor,
        long requestId,
        int offset,
        long documentStamp,
        StreamAccumulator accumulator
    ) {
        String text;
        synchronized (accumulator) {
            text = accumulator.buffer.toString();
            accumulator.buffer.setLength(0);
            accumulator.flushScheduled = false;
        }
        if (text.isEmpty()) {
            return;
        }
        ApplicationManager.getApplication().invokeLater(() ->
            appendDelta(editor, requestId, offset, documentStamp, text)
        );
    }

    private void renderSession(Editor editor, InlineSession session) {
        String text = session.remainingText;
        int newline = text.indexOf('\n');
        String firstLine = newline < 0 ? text : text.substring(0, newline);
        String rest = newline < 0 ? "" : text.substring(newline + 1);
        if (!firstLine.isEmpty()) {
            session.inlineInlay = editor.getInlayModel()
                .addInlineElement(session.offset, true, new GhostInlineRenderer(firstLine));
        }
        if (newline >= 0) {
            int anchorLine = editor.offsetToLogicalPosition(session.offset).line;
            int lineStartX = lineStartX(editor, anchorLine);
            session.blockInlay = editor.getInlayModel()
                .addBlockElement(session.offset, true, false, 0, new GhostBlockRenderer(rest, lineStartX));
        }
    }

    private InlineSession validSession(Editor editor) {
        InlineSession session = editor == null ? null : editor.getUserData(SESSION_KEY);
        if (session == null || session.remainingText.isBlank()) {
            return null;
        }
        if (editor.getCaretModel().getOffset() != session.offset
            || editor.getDocument().getModificationStamp() != session.documentStamp) {
            hide(editor);
            return null;
        }
        return session;
    }

    private void insertText(Project project, Editor editor, InlineSession session, String text) {
        WriteCommandAction.runWriteCommandAction(project, () -> {
            editor.getDocument().insertString(session.offset, text);
            editor.getCaretModel().moveToOffset(session.offset + text.length());
        });
    }

    private String nextLineChunk(String text) {
        int newline = text.indexOf('\n');
        return newline < 0 ? text : text.substring(0, newline + 1);
    }

    private String normalizeDelta(String delta) {
        if (delta == null) {
            return "";
        }
        return delta
            .replace("\r\n", "\n")
            .replace("\r", "\n");
    }

    private static String protectIncompleteTail(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        int lastNewline = text.lastIndexOf('\n');
        if (lastNewline < 0 || lastNewline == text.length() - 1) {
            return text;
        }
        String lastLine = text.substring(lastNewline + 1);
        if (!isLikelyIncompleteTail(lastLine)) {
            return text;
        }
        String protectedText = text.substring(0, lastNewline + 1);
        return protectedText.isBlank() ? text : protectedText;
    }

    private static boolean isLikelyIncompleteTail(String line) {
        String trimmed = line.stripTrailing();
        if (trimmed.isBlank()) {
            return false;
        }
        if (hasUnclosedXmlLikeTag(trimmed) || hasUnclosedQuote(trimmed, '"') || hasUnclosedQuote(trimmed, '\'')) {
            return true;
        }
        if (hasMoreOpeningThanClosing(trimmed, '(', ')')
            || hasMoreOpeningThanClosing(trimmed, '[', ']')
            || hasMoreOpeningThanClosing(trimmed, '{', '}')) {
            return true;
        }
        return endsWithDanglingToken(trimmed);
    }

    private static boolean hasUnclosedXmlLikeTag(String value) {
        int open = value.lastIndexOf('<');
        int close = value.lastIndexOf('>');
        if (open <= close || open >= value.length() - 1) {
            return false;
        }
        char next = value.charAt(open + 1);
        return Character.isLetter(next) || next == '/' || next == '!' || next == '?';
    }

    private static boolean hasUnclosedQuote(String value, char quote) {
        boolean escaped = false;
        boolean open = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == quote) {
                open = !open;
            }
        }
        return open;
    }

    private static boolean hasMoreOpeningThanClosing(String value, char open, char close) {
        int balance = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == open) {
                balance++;
            } else if (c == close && balance > 0) {
                balance--;
            }
        }
        return balance > 0;
    }

    private static boolean endsWithDanglingToken(String value) {
        return value.endsWith("=")
            || value.endsWith("+")
            || value.endsWith("-")
            || value.endsWith("*")
            || value.endsWith("/")
            || value.endsWith("%")
            || value.endsWith(".")
            || value.endsWith(",")
            || value.endsWith("&&")
            || value.endsWith("||")
            || value.endsWith("?")
            || value.endsWith(":");
    }

    @Override
    public void dispose() {
        scheduler.shutdownNow();
    }

    private static final class InlineSession {
        private int offset;
        private String rawText;
        private String remainingText;
        private long documentStamp;
        private Inlay<?> inlineInlay;
        private Inlay<?> blockInlay;
        private DocumentListener documentListener;
        private CaretListener caretListener;
        private Disposable invalidationDisposable;

        private InlineSession(int offset, String remainingText, long documentStamp) {
            this.offset = offset;
            this.rawText = remainingText;
            this.remainingText = protectIncompleteTail(remainingText);
            this.documentStamp = documentStamp;
        }

        private void appendText(String text) {
            rawText += text;
            remainingText = protectIncompleteTail(rawText);
        }

        private void consumeVisiblePrefix(String text) {
            if (rawText.startsWith(text)) {
                rawText = rawText.substring(text.length());
            } else {
                rawText = remainingText.substring(Math.min(text.length(), remainingText.length()));
            }
            remainingText = protectIncompleteTail(rawText);
        }

        private void attachInvalidationListeners(Editor editor) {
            if (invalidationDisposable != null) {
                return;
            }
            invalidationDisposable = Disposer.newDisposable("Coding Switch inline completion invalidation");
            documentListener = new DocumentListener() {
                @Override
                public void documentChanged(DocumentEvent event) {
                    AiInlineCompletionService.getInstance().hide(editor);
                }
            };
            caretListener = new CaretListener() {
                @Override
                public void caretPositionChanged(CaretEvent event) {
                    if (editor.getCaretModel().getOffset() != offset) {
                        AiInlineCompletionService.getInstance().hide(editor);
                    }
                }
            };
            editor.getDocument().addDocumentListener(documentListener, invalidationDisposable);
            editor.getCaretModel().addCaretListener(caretListener, invalidationDisposable);
        }

        private void detachInvalidationListeners(Editor editor) {
            if (invalidationDisposable != null) {
                Disposer.dispose(invalidationDisposable);
                invalidationDisposable = null;
            }
            documentListener = null;
            caretListener = null;
        }

        private void dispose(Editor editor) {
            detachInvalidationListeners(editor);
            disposeInlays();
        }

        private void disposeInlays() {
            if (inlineInlay != null) {
                inlineInlay.dispose();
                inlineInlay = null;
            }
            if (blockInlay != null) {
                blockInlay.dispose();
                blockInlay = null;
            }
        }
    }

    private static final class StreamAccumulator {
        private final StringBuilder buffer = new StringBuilder();
        private boolean flushScheduled;
    }

    private static final class GhostInlineRenderer implements EditorCustomElementRenderer {
        private final String text;

        private GhostInlineRenderer(String text) {
            this.text = text;
        }

        @Override
        public int calcWidthInPixels(Inlay inlay) {
            return textWidth(inlay, text);
        }

        @Override
        public void paint(Inlay inlay, Graphics graphics, Rectangle targetRegion, TextAttributes textAttributes) {
            graphics.setColor(GHOST_FOREGROUND);
            FontMetrics metrics = fontMetrics(inlay, editorFont(inlay));
            drawGhostText(inlay, graphics, text, targetRegion.x, targetRegion.y + metrics.getAscent());
        }
    }

    private static final class GhostBlockRenderer implements EditorCustomElementRenderer {
        private final List<String> lines;
        private final int lineStartX;

        private GhostBlockRenderer(String text, int lineStartX) {
            this.lines = splitLines(text);
            this.lineStartX = Math.max(0, lineStartX);
        }

        @Override
        public int calcWidthInPixels(Inlay inlay) {
            FontMetrics metrics = fontMetrics(inlay, editorFont(inlay));
            int width = 0;
            for (String line : lines) {
                width = Math.max(width, textWidth(inlay, line));
            }
            return Math.max(1, width + lineStartX + metrics.charWidth('m'));
        }

        @Override
        public int calcHeightInPixels(Inlay inlay) {
            return Math.max(1, lines.size()) * inlay.getEditor().getLineHeight();
        }

        @Override
        public void paint(Inlay inlay, Graphics graphics, Rectangle targetRegion, TextAttributes textAttributes) {
            graphics.setColor(GHOST_FOREGROUND);
            FontMetrics metrics = fontMetrics(inlay, editorFont(inlay));
            int y = targetRegion.y + metrics.getAscent();
            for (String line : lines) {
                if (!line.isEmpty()) {
                    drawGhostText(inlay, graphics, line, lineStartX, y);
                }
                y += inlay.getEditor().getLineHeight();
            }
        }

        private static List<String> splitLines(String text) {
            List<String> result = new ArrayList<>();
            for (String line : text.split("\\n", -1)) {
                result.add(line);
            }
            return result;
        }
    }

    private static Font editorFont(Inlay inlay) {
        return inlay.getEditor().getColorsScheme().getFont(EditorFontType.PLAIN);
    }

    private static FontMetrics fontMetrics(Inlay inlay, Font font) {
        return inlay.getEditor().getContentComponent().getFontMetrics(font);
    }

    private static int textWidth(Inlay inlay, String text) {
        Component component = inlay.getEditor().getContentComponent();
        Font editorFont = editorFont(inlay);
        int width = 0;
        int index = 0;
        while (index < text.length()) {
            int codePoint = text.codePointAt(index);
            Font font = displayFont(editorFont, codePoint);
            int next = nextFontRunEnd(text, index, font, editorFont);
            width += component.getFontMetrics(font).stringWidth(text.substring(index, next));
            index = next;
        }
        return width;
    }

    private static void drawGhostText(Inlay inlay, Graphics graphics, String text, int x, int baseline) {
        Component component = inlay.getEditor().getContentComponent();
        Font editorFont = editorFont(inlay);
        int currentX = x;
        int index = 0;
        while (index < text.length()) {
            int codePoint = text.codePointAt(index);
            Font font = displayFont(editorFont, codePoint);
            int next = nextFontRunEnd(text, index, font, editorFont);
            String run = text.substring(index, next);
            graphics.setFont(font);
            graphics.drawString(run, currentX, baseline);
            currentX += component.getFontMetrics(font).stringWidth(run);
            index = next;
        }
    }

    private static int nextFontRunEnd(String text, int start, Font font, Font editorFont) {
        int index = start;
        while (index < text.length()) {
            int codePoint = text.codePointAt(index);
            if (!displayFont(editorFont, codePoint).equals(font)) {
                break;
            }
            index += Character.charCount(codePoint);
        }
        return index;
    }

    private static Font displayFont(Font editorFont, int codePoint) {
        if (editorFont.canDisplay(codePoint)) {
            return editorFont;
        }
        Font monospaced = new Font(Font.MONOSPACED, editorFont.getStyle(), editorFont.getSize());
        if (monospaced.canDisplay(codePoint)) {
            return monospaced;
        }
        return new Font(Font.DIALOG, editorFont.getStyle(), editorFont.getSize());
    }

    private static int lineStartX(Editor editor, int line) {
        return editor.logicalPositionToXY(new LogicalPosition(Math.max(0, line), 0)).x;
    }
}
