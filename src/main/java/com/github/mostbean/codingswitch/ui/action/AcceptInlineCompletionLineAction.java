package com.github.mostbean.codingswitch.ui.action;

import com.github.mostbean.codingswitch.service.AiInlineCompletionService;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

public class AcceptInlineCompletionLineAction extends DumbAwareAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        if (project == null || editor == null) {
            return;
        }
        AiInlineCompletionService.getInstance().acceptLine(project, editor);
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        e.getPresentation().setEnabled(
            e.getProject() != null
                && editor != null
                && AiInlineCompletionService.getInstance().hasActiveCompletion(editor)
        );
    }
}
