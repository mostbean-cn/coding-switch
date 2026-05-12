package com.github.mostbean.codingswitch.completion;

import com.github.mostbean.codingswitch.service.AiFeatureSettings;
import com.github.mostbean.codingswitch.service.AiInlineCompletionService;
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;

public class AiInlineCompletionTypedHandler extends TypedHandlerDelegate {

    @Override
    public @NotNull Result charTyped(char c, @NotNull Project project, @NotNull Editor editor, @NotNull PsiFile file) {
        AiInlineCompletionService.getInstance().hide(editor);
        if (shouldSchedule(c)) {
            AiInlineCompletionService.getInstance().scheduleAuto(project, editor);
        }
        return Result.CONTINUE;
    }

    private boolean shouldSchedule(char c) {
        if (!AiFeatureSettings.getInstance().isCodeCompletionEnabled()
            || !AiFeatureSettings.getInstance().isAutoCompletionEnabled()) {
            return false;
        }
        return Character.isLetterOrDigit(c)
            || Character.isWhitespace(c)
            || c == '.'
            || c == '_'
            || c == ')'
            || c == ']'
            || c == '}'
            || c == ':'
            || c == ';';
    }
}
