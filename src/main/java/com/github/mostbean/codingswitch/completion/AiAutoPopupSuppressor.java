package com.github.mostbean.codingswitch.completion;

import com.github.mostbean.codingswitch.service.AiFeatureSettings;
import com.intellij.codeInsight.completion.CompletionConfidence;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.util.ThreeState;
import org.jetbrains.annotations.NotNull;

public final class AiAutoPopupSuppressor extends CompletionConfidence {

    @Override
    public @NotNull ThreeState shouldSkipAutopopup(
        @NotNull PsiElement contextElement,
        @NotNull PsiFile psiFile,
        int offset
    ) {
        AiFeatureSettings settings = AiFeatureSettings.getInstance();
        if (settings.isCodeCompletionEnabled() && settings.isAutoCompletionEnabled()) {
            return ThreeState.YES;
        }
        return ThreeState.UNSURE;
    }
}
