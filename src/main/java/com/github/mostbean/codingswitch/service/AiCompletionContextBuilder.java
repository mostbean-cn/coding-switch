package com.github.mostbean.codingswitch.service;

import com.github.mostbean.codingswitch.model.AiCompletionTriggerMode;
import com.github.mostbean.codingswitch.model.AiCompletionLengthLevel;
import com.github.mostbean.codingswitch.model.AiModelFormat;
import com.github.mostbean.codingswitch.model.AiModelProfile;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;

final class AiCompletionContextBuilder {

    private static final int APPROX_CHARS_PER_TOKEN = 4;
    private static final int MIN_CONTEXT_CHARS = 512;
    private static final int PREFIX_BUDGET_PERCENT = 70;
    private static final int SUFFIX_BUDGET_PERCENT = 20;
    private static final int MAX_HEADER_SCAN_LINES = 80;

    private AiCompletionContextBuilder() {
    }

    static Context build(
        Project project,
        Editor editor,
        AiCompletionTriggerMode triggerMode,
        AiCompletionLengthLevel lengthLevel
    ) {
        AiModelProfile profile = AiFeatureSettings.getInstance().getActiveCompletionProfile();
        boolean useNativeFim = profile != null
            && (profile.getFormat() == AiModelFormat.FIM_COMPLETIONS
            || profile.getFormat() == AiModelFormat.FIM_CHAT_COMPLETIONS);
        boolean useFim = profile != null && profile.isFimEnabled() && !useNativeFim;

        Document document = editor.getDocument();
        int offset = Math.max(0, Math.min(editor.getCaretModel().getOffset(), document.getTextLength()));
        String text = document.getText();
        ContextSlices slices = buildContextSlices(text, offset);
        String prefix = slices.prefix();
        String suffix = slices.suffix();

        PsiFile psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document);
        String language = psiFile == null ? "unknown" : psiFile.getLanguage().getDisplayName();
        VirtualFile file = psiFile == null ? null : psiFile.getVirtualFile();
        String path = file == null ? "" : file.getPath();

        String systemPrompt;
        String userPrompt;

        if (useFim) {
            systemPrompt = """
                You are a code completion engine inside a JetBrains IDE.
                You are given a code snippet with a missing part between FIM markers.
                Return only the missing code that should be inserted between the markers.
                Do not repeat the existing code, do not add markdown fences, and do not explain.
                Keep the completion concise and syntactically consistent with the surrounding file.
                %s
                """.formatted(lengthLevel.getPromptHint());

            userPrompt = """
                Trigger: %s
                Language: %s
                File: %s

                %s
                %s%s%s%s%s
                """.formatted(
                triggerMode.name().toLowerCase(),
                language,
                path,
                "",
                profile.getFimPrefixToken(),
                prefix,
                profile.getFimSuffixToken(),
                suffix,
                profile.getFimMiddleToken()
            );
        } else {
            systemPrompt = """
                You are a code completion engine inside a JetBrains IDE.
                Return only the code/text suffix that should be inserted at the caret.
                Do not repeat the existing prefix, do not add markdown fences, and do not explain.
                Keep the completion concise and syntactically consistent with the surrounding file.
                %s
                """.formatted(lengthLevel.getPromptHint());

            userPrompt = """
                Trigger: %s
                Language: %s
                File: %s

                %s
                Text before caret:
                %s
                <caret>
                Text after caret:
                %s
                """.formatted(
                triggerMode.name().toLowerCase(),
                language,
                path,
                "",
                prefix,
                suffix
            );
        }

        return new Context(systemPrompt, userPrompt, path, prefix, suffix);
    }

    private static ContextSlices buildContextSlices(String text, int offset) {
        int totalContextChars = Math.max(
            MIN_CONTEXT_CHARS,
            AiFeatureSettings.getInstance().getTimingConfig().getMaxPromptTokens() * APPROX_CHARS_PER_TOKEN
        );
        int prefixBudget = Math.max(1, totalContextChars * PREFIX_BUDGET_PERCENT / 100);
        int suffixBudget = Math.max(1, totalContextChars * SUFFIX_BUDGET_PERCENT / 100);
        int headerBudget = Math.max(0, totalContextChars - prefixBudget - suffixBudget);

        int prefixStart = Math.max(0, offset - prefixBudget);
        int suffixEnd = Math.min(text.length(), offset + suffixBudget);
        String localPrefix = text.substring(prefixStart, offset);
        String suffix = text.substring(offset, suffixEnd);
        String header = prefixStart > 0 && headerBudget > 0
            ? fileHeaderContext(text, prefixStart, headerBudget)
            : "";
        String prefix = header.isBlank()
            ? localPrefix
            : header + "\n\n...\n" + localPrefix;
        return new ContextSlices(prefix, suffix);
    }

    private static String fileHeaderContext(String text, int beforeOffset, int budget) {
        int headerEnd = Math.min(findHeaderEnd(text), beforeOffset);
        if (headerEnd <= 0) {
            return "";
        }
        return takeHeadByLine(text.substring(0, headerEnd).stripTrailing(), budget);
    }

    private static int findHeaderEnd(String text) {
        int position = 0;
        int headerEnd = 0;
        boolean sawHeaderLine = false;
        for (int lineCount = 0; position < text.length() && lineCount < MAX_HEADER_SCAN_LINES; lineCount++) {
            int lineEnd = text.indexOf('\n', position);
            if (lineEnd < 0) {
                lineEnd = text.length();
            }
            String line = text.substring(position, lineEnd);
            String trimmed = line.strip();
            boolean headerLine = isHeaderLine(trimmed);
            boolean skippableBeforeCode = trimmed.isEmpty() || isCommentLine(trimmed);
            if (headerLine) {
                sawHeaderLine = true;
                headerEnd = lineEnd < text.length() ? lineEnd + 1 : lineEnd;
            } else if (sawHeaderLine && skippableBeforeCode) {
                headerEnd = lineEnd < text.length() ? lineEnd + 1 : lineEnd;
            } else if (!sawHeaderLine && skippableBeforeCode) {
                headerEnd = lineEnd < text.length() ? lineEnd + 1 : lineEnd;
            } else {
                break;
            }
            position = lineEnd < text.length() ? lineEnd + 1 : lineEnd;
        }
        return sawHeaderLine ? headerEnd : 0;
    }

    private static boolean isHeaderLine(String line) {
        return line.startsWith("package ")
            || line.startsWith("import ")
            || line.startsWith("from ")
            || line.startsWith("using ")
            || line.startsWith("#include")
            || line.startsWith("require(")
            || line.startsWith("const ") && line.contains(" require(")
            || line.startsWith("let ") && line.contains(" require(")
            || line.startsWith("var ") && line.contains(" require(")
            || line.startsWith("namespace ");
    }

    private static boolean isCommentLine(String line) {
        return line.startsWith("//")
            || line.startsWith("/*")
            || line.startsWith("*")
            || line.startsWith("#")
            || line.startsWith("--");
    }

    private static String takeHeadByLine(String text, int maxChars) {
        if (text.length() <= maxChars) {
            return text;
        }
        int end = text.lastIndexOf('\n', maxChars);
        if (end < maxChars / 2) {
            end = maxChars;
        }
        return text.substring(0, end).stripTrailing();
    }

    private record ContextSlices(String prefix, String suffix) {
    }

    record Context(String systemPrompt, String userPrompt, String filePath, String fimPrefix, String fimSuffix) {
    }
}
