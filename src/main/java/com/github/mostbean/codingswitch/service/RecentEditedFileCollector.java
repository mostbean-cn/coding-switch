package com.github.mostbean.codingswitch.service;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import java.util.ArrayList;
import java.util.List;

/**
 * 收集最近编辑文件的上下文。
 */
public class RecentEditedFileCollector implements ContextCollector {

    private static final int MAX_FILES = 3;
    private static final int MAX_CHARS_PER_FILE = 500;

    @Override
    public int getPriority() {
        return 10;
    }

    @Override
    public String collect(Project project, Editor editor, int cursorOffset) {
        if (project == null || editor == null) {
            return "";
        }

        // 未启用补全功能时，跳过所有工作
        if (!AiFeatureSettings.getInstance().isCodeCompletionEnabled()) {
            return "";
        }

        VirtualFile currentFile = editor.getVirtualFile();
        if (currentFile == null) {
            return "";
        }

        List<String> contexts = new ArrayList<>();
        FileEditorManager fileEditorManager = FileEditorManager.getInstance(project);

        for (var fileEditor : fileEditorManager.getAllEditors()) {
            if (contexts.size() >= MAX_FILES) {
                break;
            }

            if (!(fileEditor instanceof TextEditor textEditor)) {
                continue;
            }

            Editor recentEditor = textEditor.getEditor();
            VirtualFile recentFile = recentEditor.getVirtualFile();

            if (recentFile == null || recentFile.equals(currentFile)) {
                continue;
            }

            String text = recentEditor.getDocument().getText();
            if (text.length() > MAX_CHARS_PER_FILE) {
                text = text.substring(0, MAX_CHARS_PER_FILE) + "...";
            }

            contexts.add("File: " + recentFile.getName() + "\n" + text);
        }

        if (contexts.isEmpty()) {
            return "";
        }

        return "Recent edited files:\n" + String.join("\n\n", contexts);
    }
}
