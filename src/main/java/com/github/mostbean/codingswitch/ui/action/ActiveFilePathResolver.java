package com.github.mostbean.codingswitch.ui.action;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.LangDataKeys;
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFileSystemItem;
import java.lang.reflect.Method;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 解析当前上下文中的文件或目录，并转换为相对项目根目录的路径文本。
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
        if (editor == null) {
            return Resolution.unavailable("cliQuickLaunch.insertFilePathWithLine.noEditor");
        }

        int startLineNumber;
        int endLineNumber;
        if (editor.getSelectionModel().hasSelection()) {
            startLineNumber = editor.getDocument().getLineNumber(
                editor.getSelectionModel().getSelectionStart()
            ) + 1;
            endLineNumber = resolveSelectionEndLine(editor);
        } else {
            startLineNumber = editor.getCaretModel().getLogicalPosition().line + 1;
            endLineNumber = startLineNumber;
        }

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

        if (hasMultipleContextFiles(e)) {
            return PathResolution.unavailable("cliQuickLaunch.insertFilePath.noActiveFile");
        }

        VirtualFile activeFile = resolveContextFile(e);
        if (activeFile == null) {
            activeFile = resolveSelectedEditorFile(project);
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

    private static @Nullable VirtualFile resolveContextFile(@NotNull AnActionEvent e) {
        VirtualFile[] selectedFiles = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY);
        if (selectedFiles != null && selectedFiles.length == 1) {
            return selectedFiles[0];
        }

        VirtualFile selectedFile = e.getData(CommonDataKeys.VIRTUAL_FILE);
        if (selectedFile != null) {
            return selectedFile;
        }

        PsiElement[] selectedElements = e.getData(LangDataKeys.PSI_ELEMENT_ARRAY);
        if (selectedElements != null && selectedElements.length == 1) {
            return resolveVirtualFile(selectedElements[0]);
        }

        VirtualFile selectedItemFile = resolveSingleSelectedItemFile(e);
        if (selectedItemFile != null) {
            return selectedItemFile;
        }

        VirtualFile psiElementFile = resolveVirtualFile(e.getData(LangDataKeys.PSI_ELEMENT));
        if (psiElementFile != null) {
            return psiElementFile;
        }

        return resolveVirtualFile(e.getData(LangDataKeys.TARGET_PSI_ELEMENT));
    }

    private static boolean hasMultipleContextFiles(@NotNull AnActionEvent e) {
        VirtualFile[] selectedFiles = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY);
        if (selectedFiles != null && selectedFiles.length > 1) {
            return true;
        }

        PsiElement[] selectedElements = e.getData(LangDataKeys.PSI_ELEMENT_ARRAY);
        if (selectedElements != null && selectedElements.length > 1) {
            return true;
        }

        Object[] selectedItems = e.getData(PlatformCoreDataKeys.SELECTED_ITEMS);
        return selectedItems != null && selectedItems.length > 1;
    }

    private static @Nullable VirtualFile resolveSingleSelectedItemFile(@NotNull AnActionEvent e) {
        Object[] selectedItems = e.getData(PlatformCoreDataKeys.SELECTED_ITEMS);
        if (selectedItems != null && selectedItems.length == 1) {
            return resolveVirtualFile(selectedItems[0]);
        }
        return resolveVirtualFile(e.getData(PlatformCoreDataKeys.SELECTED_ITEM));
    }

    private static @Nullable VirtualFile resolveVirtualFile(@Nullable PsiElement element) {
        if (element instanceof PsiFileSystemItem fileSystemItem) {
            return fileSystemItem.getVirtualFile();
        }
        return null;
    }

    private static @Nullable VirtualFile resolveVirtualFile(@Nullable Object item) {
        if (item instanceof VirtualFile virtualFile) {
            return virtualFile;
        }
        if (item instanceof PsiElement psiElement) {
            return resolveVirtualFile(psiElement);
        }

        VirtualFile directFile = invokeVirtualFileGetter(item, "getVirtualFile");
        if (directFile != null) {
            return directFile;
        }

        Object value = invokeGetter(item, "getValue");
        if (value != null && value != item) {
            return resolveVirtualFile(value);
        }

        Object element = invokeGetter(item, "getElement");
        if (element != null && element != item) {
            return resolveVirtualFile(element);
        }
        return null;
    }

    private static @Nullable VirtualFile invokeVirtualFileGetter(@Nullable Object target, @NotNull String methodName) {
        Object value = invokeGetter(target, methodName);
        return value instanceof VirtualFile virtualFile ? virtualFile : null;
    }

    private static @Nullable Object invokeGetter(@Nullable Object target, @NotNull String methodName) {
        if (target == null) {
            return null;
        }
        try {
            Method method = target.getClass().getMethod(methodName);
            if (method.getParameterCount() != 0) {
                return null;
            }
            return method.invoke(target);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static @Nullable VirtualFile resolveSelectedEditorFile(@NotNull Project project) {
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
            String relativePath = basePath.relativize(filePath).toString().replace('\\', '/');
            return relativePath.isBlank() ? "." : relativePath;
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
