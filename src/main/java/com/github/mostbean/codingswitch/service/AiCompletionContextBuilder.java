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

    private static final int MAX_PREFIX_CHARS = 6000;
    private static final int MAX_SUFFIX_CHARS = 1600;

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
            && (profile.getFormat() == AiModelFormat.DEEPSEEK_FIM_COMPLETIONS
            || profile.getFormat() == AiModelFormat.FIM_CHAT_COMPLETIONS);
        boolean useFim = profile != null && profile.isFimEnabled() && !useNativeFim;

        Document document = editor.getDocument();
        int offset = Math.max(0, Math.min(editor.getCaretModel().getOffset(), document.getTextLength()));
        String text = document.getText();
        int prefixStart = Math.max(0, offset - MAX_PREFIX_CHARS);
        int suffixEnd = Math.min(text.length(), offset + MAX_SUFFIX_CHARS);
        String prefix = text.substring(prefixStart, offset);
        String suffix = text.substring(offset, suffixEnd);

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

    record Context(String systemPrompt, String userPrompt, String filePath, String fimPrefix, String fimSuffix) {
    }
}
