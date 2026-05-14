package com.github.mostbean.codingswitch.completion;

import com.github.mostbean.codingswitch.service.AiFeatureSettings;
import com.github.mostbean.codingswitch.service.AiCompletionEditorGuard;
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
        if (AiCompletionEditorGuard.isEligible(project, editor) && shouldSchedule(c)) {
            AiInlineCompletionService.getInstance().scheduleAuto(project, editor);
        }
        return Result.CONTINUE;
    }

    @Override
    public @NotNull Result checkAutoPopup(char c, @NotNull Project project, @NotNull Editor editor, @NotNull PsiFile file) {
        return shouldSuppressIdeAutoPopup(project, editor) ? Result.STOP : Result.CONTINUE;
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
            || c == '('
            || c == '['
            || c == '{'
            || c == ')'
            || c == ']'
            || c == '}'
            || c == ':'
            || c == ';';
    }

    private boolean shouldSuppressIdeAutoPopup(Project project, Editor editor) {
        AiFeatureSettings settings = AiFeatureSettings.getInstance();
        return settings.isCodeCompletionEnabled()
            && settings.isAutoCompletionEnabled()
            && AiCompletionEditorGuard.isEligible(project, editor);
    }
}
