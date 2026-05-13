package com.github.mostbean.codingswitch.service;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorKind;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import java.util.Locale;
import java.util.Set;

/**
 * 限制 AI 代码补全只在真实代码编辑器中触发。
 */
public final class AiCompletionEditorGuard {

    private static final Set<String> EXCLUDED_EXTENSIONS = Set.of(
        "txt", "text", "md", "markdown", "rst", "adoc", "log", "csv", "tsv"
    );
    private static final Set<String> EXCLUDED_FILE_TYPE_NAMES = Set.of(
        "plain text", "markdown", "text"
    );
    private static final Set<String> EXCLUDED_LANGUAGE_IDS = Set.of(
        "text", "markdown", "regexp"
    );
    private static final Set<String> EXCLUDED_NAME_FRAGMENTS = Set.of(
        "commit_editmsg", "merge_msg", "squash_msg", "tag_editmsg"
    );

    private AiCompletionEditorGuard() {
    }

    public static boolean isEligible(Project project, Editor editor) {
        if (project == null || editor == null || editor.isDisposed() || editor.isViewer()) {
            return false;
        }
        if (editor.getEditorKind() != EditorKind.MAIN_EDITOR) {
            return false;
        }

        return ReadAction.computeCancellable(() -> isEligibleFile(project, editor));
    }

    private static boolean isEligibleFile(Project project, Editor editor) {
        Document document = editor.getDocument();
        VirtualFile file = FileDocumentManager.getInstance().getFile(document);
        if (file == null || !file.isValid() || file.isDirectory()) {
            return false;
        }
        if (!file.isInLocalFileSystem()) {
            return false;
        }

        PsiFile psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document);
        if (psiFile == null || !psiFile.isPhysical()) {
            return false;
        }

        return !isExcludedFile(file, psiFile);
    }

    private static boolean isExcludedFile(VirtualFile file, PsiFile psiFile) {
        String fileName = normalize(file.getName());
        for (String fragment : EXCLUDED_NAME_FRAGMENTS) {
            if (fileName.contains(fragment)) {
                return true;
            }
        }

        String extension = file.getExtension();
        if (extension != null && EXCLUDED_EXTENSIONS.contains(normalize(extension))) {
            return true;
        }

        String fileTypeName = normalize(file.getFileType().getName());
        if (EXCLUDED_FILE_TYPE_NAMES.contains(fileTypeName)) {
            return true;
        }

        String languageId = normalize(psiFile.getLanguage().getID());
        return EXCLUDED_LANGUAGE_IDS.contains(languageId);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
