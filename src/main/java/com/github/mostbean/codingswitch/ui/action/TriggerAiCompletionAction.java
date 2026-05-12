package com.github.mostbean.codingswitch.ui.action;

import com.github.mostbean.codingswitch.service.AiCompletionTriggerState;
import com.github.mostbean.codingswitch.service.AiCompletionService;
import com.intellij.codeInsight.completion.CodeCompletionHandlerBase;
import com.intellij.codeInsight.completion.CompletionType;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

public class TriggerAiCompletionAction extends DumbAwareAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        if (project == null || editor == null) {
            return;
        }
        if (AiCompletionService.getInstance().isCompletionInProgress(project, editor)) {
            return;
        }
        AiCompletionTriggerState.markManual(editor);
        new CodeCompletionHandlerBase(CompletionType.BASIC).invokeCompletion(project, editor);
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        e.getPresentation().setEnabled(
            project != null
                && editor != null
                && !AiCompletionService.getInstance().isCompletionInProgress(project, editor)
        );
    }
}
