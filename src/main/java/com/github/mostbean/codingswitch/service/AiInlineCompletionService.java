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
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.ui.JBColor;
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

    private static final long AUTO_TRIGGER_DELAY_MS = 650;
    private static final long STREAM_RENDER_THROTTLE_MS = 45;
    private static final Key<InlineSession> SESSION_KEY = Key.create("coding.switch.ai.inline.session");
    private static final Key<Long> REQUEST_ID_KEY = Key.create("coding.switch.ai.inline.request.id");

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
        scheduler.schedule(
            () -> ApplicationManager.getApplication().invokeLater(() -> {
                Long current = editor.getUserData(REQUEST_ID_KEY);
                if (current != null && current == requestId) {
                    request(project, editor, AiCompletionTriggerMode.AUTO, requestId);
                }
            }),
            AUTO_TRIGGER_DELAY_MS,
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
        session.disposeInlays();
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
        insertText(project, editor, session, chunk);
        session.remainingText = session.remainingText.substring(chunk.length());
        session.offset += chunk.length();
        session.documentStamp = editor.getDocument().getModificationStamp();
        session.disposeInlays();
        if (session.remainingText.isBlank()) {
            editor.putUserData(SESSION_KEY, null);
        } else {
            renderSession(editor, session);
        }
        return true;
    }

    private void request(Project project, Editor editor, AiCompletionTriggerMode triggerMode, long requestId) {
        if (AiCompletionService.getInstance().isCompletionInProgress(project, editor)) {
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
        } else {
            session.remainingText += text;
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
        scheduler.schedule(
            () -> flushDelta(editor, requestId, offset, documentStamp, accumulator),
            STREAM_RENDER_THROTTLE_MS,
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
        if (!rest.isBlank()) {
            int indentX = editor.logicalPositionToXY(editor.offsetToLogicalPosition(session.offset)).x;
            session.blockInlay = editor.getInlayModel()
                .addBlockElement(session.offset, true, false, 0, new GhostBlockRenderer(rest, indentX));
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

    @Override
    public void dispose() {
        scheduler.shutdownNow();
    }

    private static final class InlineSession {
        private int offset;
        private String remainingText;
        private long documentStamp;
        private Inlay<?> inlineInlay;
        private Inlay<?> blockInlay;

        private InlineSession(int offset, String remainingText, long documentStamp) {
            this.offset = offset;
            this.remainingText = remainingText;
            this.documentStamp = documentStamp;
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
            return fontMetrics(inlay).stringWidth(text);
        }

        @Override
        public void paint(Inlay inlay, Graphics graphics, Rectangle targetRegion, TextAttributes textAttributes) {
            graphics.setFont(editorFont(inlay));
            graphics.setColor(JBColor.GRAY);
            FontMetrics metrics = graphics.getFontMetrics();
            graphics.drawString(text, targetRegion.x, targetRegion.y + metrics.getAscent());
        }
    }

    private static final class GhostBlockRenderer implements EditorCustomElementRenderer {
        private final List<String> lines;
        private final int indentX;

        private GhostBlockRenderer(String text, int indentX) {
            this.lines = splitLines(text);
            this.indentX = Math.max(0, indentX);
        }

        @Override
        public int calcWidthInPixels(Inlay inlay) {
            FontMetrics metrics = fontMetrics(inlay);
            int width = 0;
            for (String line : lines) {
                width = Math.max(width, metrics.stringWidth(line));
            }
            return indentX + width;
        }

        @Override
        public int calcHeightInPixels(Inlay inlay) {
            return Math.max(1, lines.size()) * inlay.getEditor().getLineHeight();
        }

        @Override
        public void paint(Inlay inlay, Graphics graphics, Rectangle targetRegion, TextAttributes textAttributes) {
            graphics.setFont(editorFont(inlay));
            graphics.setColor(JBColor.GRAY);
            FontMetrics metrics = graphics.getFontMetrics();
            int y = targetRegion.y + metrics.getAscent();
            for (String line : lines) {
                graphics.drawString(line, targetRegion.x + indentX, y);
                y += inlay.getEditor().getLineHeight();
            }
        }

        private static List<String> splitLines(String text) {
            List<String> result = new ArrayList<>();
            for (String line : text.split("\\n", -1)) {
                if (!line.isEmpty()) {
                    result.add(line);
                }
            }
            return result;
        }
    }

    private static Font editorFont(Inlay inlay) {
        return inlay.getEditor().getContentComponent().getFont();
    }

    private static FontMetrics fontMetrics(Inlay inlay) {
        return inlay.getEditor().getContentComponent().getFontMetrics(editorFont(inlay));
    }
}
