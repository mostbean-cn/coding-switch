package com.github.mostbean.codingswitch.ui.action;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 解析当前上下文中的活动文件，并转换为相对项目根目录的路径文本。
 */
public final class ActiveFilePathResolver {

    private ActiveFilePathResolver() {}

    public static @NotNull Resolution resolve(@NotNull AnActionEvent e) {
        PathResolution pathResolution = resolveRelativePath(e);
        if (!pathResolution.available() || pathResolution.relativePath() == null) {
            return Resolution.unavailable(pathResolution.messageKey());
        }
        return Resolution.available(quoteIfNeeded(pathResolution.relativePath()));
    }

    public static @NotNull Resolution resolveWithLine(@NotNull AnActionEvent e) {
        PathResolution pathResolution = resolveRelativePath(e);
        if (!pathResolution.available() || pathResolution.relativePath() == null) {
            return Resolution.unavailable(pathResolution.messageKey());
        }

        Editor editor = e.getData(CommonDataKeys.EDITOR);
        if (editor == null || !editor.getSelectionModel().hasSelection()) {
            return Resolution.unavailable("cliQuickLaunch.insertFilePathWithLine.noSelection");
        }

        int startLineNumber = editor.getDocument().getLineNumber(
            editor.getSelectionModel().getSelectionStart()
        ) + 1;
        int endLineNumber = resolveSelectionEndLine(editor);
        String lineSuffix = startLineNumber == endLineNumber
            ? ":" + startLineNumber
            : ":" + startLineNumber + "-" + endLineNumber;
        return Resolution.available(
            quoteIfNeeded(pathResolution.relativePath() + lineSuffix)
        );
    }

    private static int resolveSelectionEndLine(@NotNull Editor editor) {
        int selectionStart = editor.getSelectionModel().getSelectionStart();
        int selectionEnd = editor.getSelectionModel().getSelectionEnd();
        int endOffset = selectionEnd;
        if (selectionEnd > selectionStart) {
            endOffset = selectionEnd - 1;
        }
        return editor.getDocument().getLineNumber(endOffset) + 1;
    }

    private static @NotNull PathResolution resolveRelativePath(@NotNull AnActionEvent e) {
        Project project = e.getData(CommonDataKeys.PROJECT);
        if (project == null || project.getBasePath() == null || project.getBasePath().isBlank()) {
            return PathResolution.unavailable("cliQuickLaunch.insertFilePath.noProject");
        }

        VirtualFile activeFile = resolveSelectedFile(project);
        if (activeFile == null) {
            activeFile = e.getData(CommonDataKeys.VIRTUAL_FILE);
        }
        if (activeFile != null && activeFile.isDirectory()) {
            return PathResolution.unavailable("cliQuickLaunch.insertFilePath.noActiveFile");
        }
        if (activeFile == null) {
            return PathResolution.unavailable("cliQuickLaunch.insertFilePath.noActiveFile");
        }

        String relativePath = toRelativePath(project.getBasePath(), activeFile);
        if (relativePath == null || relativePath.isBlank()) {
            return PathResolution.unavailable("cliQuickLaunch.insertFilePath.fileOutOfProject");
        }

        return PathResolution.available(relativePath);
    }

    private static @Nullable VirtualFile resolveSelectedFile(@NotNull Project project) {
        FileEditorManager editorManager = FileEditorManager.getInstance(project);
        VirtualFile[] selectedFiles = editorManager.getSelectedFiles();
        return selectedFiles.length > 0 ? selectedFiles[0] : null;
    }

    private static @Nullable String toRelativePath(
        @NotNull String projectBasePath,
        @NotNull VirtualFile file
    ) {
        if (!file.isInLocalFileSystem()) {
            return null;
        }

        try {
            Path basePath = Path.of(projectBasePath).toAbsolutePath().normalize();
            Path filePath = Path.of(file.getPath()).toAbsolutePath().normalize();
            if (!filePath.startsWith(basePath)) {
                return null;
            }
            return basePath.relativize(filePath).toString().replace('\\', '/');
        } catch (InvalidPathException ignored) {
            return null;
        }
    }

    private static @NotNull String quoteIfNeeded(@NotNull String path) {
        return needsQuoting(path) ? "\"" + path + "\"" : path;
    }

    private static boolean needsQuoting(@NotNull String path) {
        for (int i = 0; i < path.length(); i++) {
            char ch = path.charAt(i);
            if (Character.isWhitespace(ch) || "&|;<>()[{}]$`".indexOf(ch) >= 0) {
                return true;
            }
        }
        return false;
    }

    public record Resolution(
        boolean available,
        @Nullable String pathText,
        @NotNull String messageKey
    ) {
        private static @NotNull Resolution available(@NotNull String pathText) {
            return new Resolution(true, pathText, "");
        }

        private static @NotNull Resolution unavailable(@NotNull String messageKey) {
            return new Resolution(false, null, messageKey);
        }
    }

    private record PathResolution(
        boolean available,
        @Nullable String relativePath,
        @NotNull String messageKey
    ) {
        private static @NotNull PathResolution available(@NotNull String relativePath) {
            return new PathResolution(true, relativePath, "");
        }

        private static @NotNull PathResolution unavailable(@NotNull String messageKey) {
            return new PathResolution(false, null, messageKey);
        }
    }
}
