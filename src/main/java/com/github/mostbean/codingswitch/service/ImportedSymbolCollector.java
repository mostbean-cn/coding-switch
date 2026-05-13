package com.github.mostbean.codingswitch.service;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 收集导入符号的上下文（通用实现，不依赖特定语言插件）。
 */
public class ImportedSymbolCollector implements ContextCollector {

    private static final int MAX_IMPORTS = 10;
    private static final Pattern IMPORT_PATTERN = Pattern.compile("^\\s*import\\s+[\\w.]+(?:\\.\\*)?\\s*;", Pattern.MULTILINE);

    @Override
    public int getPriority() {
        return 20;
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

        PsiFile psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument());
        if (psiFile == null) {
            return "";
        }

        String text = editor.getDocument().getText();
        Matcher matcher = IMPORT_PATTERN.matcher(text);

        StringBuilder imports = new StringBuilder();
        int count = 0;

        while (matcher.find() && count < MAX_IMPORTS) {
            if (matcher.start() >= cursorOffset) {
                break;
            }
            if (imports.length() > 0) {
                imports.append("\n");
            }
            imports.append(matcher.group().trim());
            count++;
        }

        if (imports.length() == 0) {
            return "";
        }

        return "Imports:\n" + imports;
    }
}
