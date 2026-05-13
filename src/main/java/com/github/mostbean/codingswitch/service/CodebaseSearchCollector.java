package com.github.mostbean.codingswitch.service;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileVisitor;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 基于 TF-IDF 的代码库检索收集器。
 */
public class CodebaseSearchCollector implements ContextCollector {

    private static final int MAX_RESULTS = 5;
    private static final int MAX_CHARS_PER_RESULT = 300;
    private static final Set<String> EXCLUDED_DIRS = Set.of(
        "node_modules", "build", "dist", "out", "target",
        ".git", ".idea", ".gradle", ".svn", "__pycache__"
    );
    private static final Set<String> EXCLUDED_EXTENSIONS = Set.of(
        "class", "jar", "zip", "tar", "gz", "png", "jpg", "gif",
        "ico", "svg", "woff", "woff2", "ttf", "eot"
    );

    private final TfIdfIndex index = new TfIdfIndex();
    private final Set<String> indexedFiles = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> fileModificationTimes = new ConcurrentHashMap<>();
    private final AtomicBoolean indexingInProgress = new AtomicBoolean(false);
    private final AtomicBoolean metadataLoaded = new AtomicBoolean(false);
    private volatile long lastIndexTime = 0;

    @Override
    public int getPriority() {
        return 5; // 高于 RecentEditedFileCollector 和 ImportedSymbolCollector
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

        ensureIndexLoaded(project);

        // 异步更新索引
        triggerIndexUpdate(project);

        // 获取当前文件的语言和内容作为查询
        PsiFile currentFile = ReadAction.compute(() ->
            PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument())
        );
        if (currentFile == null) {
            return "";
        }

        String currentContent = editor.getDocument().getText();

        // 使用光标周围的文本作为查询
        String query = extractQueryContext(currentContent, cursorOffset);
        if (query.isBlank()) {
            return "";
        }

        // 搜索相关代码块
        List<TfIdfIndex.SearchResult> results = index.search(query, MAX_RESULTS);
        if (results.isEmpty()) {
            return "";
        }

        StringBuilder context = new StringBuilder();
        context.append("Related code from codebase:\n");

        for (TfIdfIndex.SearchResult result : results) {
            CodeChunk chunk = result.chunk();
            String content = chunk.content();
            if (content.length() > MAX_CHARS_PER_RESULT) {
                content = content.substring(0, MAX_CHARS_PER_RESULT) + "...";
            }
            context.append("\n// File: ").append(chunk.fileName())
                .append(" (lines ").append(chunk.startLine())
                .append("-").append(chunk.endLine()).append(")\n")
                .append(content).append("\n");
        }

        return context.toString();
    }

    private String extractQueryContext(String content, int offset) {
        // 提取光标前后的关键文本作为查询
        int start = Math.max(0, offset - 200);
        int end = Math.min(content.length(), offset + 100);
        String context = content.substring(start, end);

        // 提取标识符作为查询词
        StringBuilder query = new StringBuilder();
        var matcher = java.util.regex.Pattern.compile("[a-zA-Z_]\\w{3,}").matcher(context);
        int count = 0;
        while (matcher.find() && count < 10) {
            if (query.length() > 0) {
                query.append(" ");
            }
            query.append(matcher.group());
            count++;
        }

        return query.toString();
    }

    public void ensureIndexLoaded(Project project) {
        if (project != null && metadataLoaded.compareAndSet(false, true)) {
            loadIndexMetadata(project);
        }
    }

    private void loadIndexMetadata(Project project) {
        IndexPersistence.IndexData data = IndexPersistence.load(project);
        if (data != null) {
            fileModificationTimes.putAll(data.getFileModificationTimes());
            if (data.getIndexSnapshot() != null) {
                index.restore(data.getIndexSnapshot());
                for (CodeChunk chunk : data.getIndexSnapshot().documents().values()) {
                    indexedFiles.add(chunk.filePath());
                }
            }
            lastIndexTime = data.getSaveTime();
        }
    }

    private void triggerIndexUpdate(Project project) {
        // 限制索引更新频率（至少间隔30秒）
        long now = System.currentTimeMillis();
        if (index.getDocumentCount() > 0 && now - lastIndexTime < 30000) {
            return;
        }

        if (!indexingInProgress.compareAndSet(false, true)) {
            return;
        }

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                updateIndex(project);
                lastIndexTime = System.currentTimeMillis();
                // 索引完成后保存元数据
                IndexPersistence.save(project, index, fileModificationTimes);
            } finally {
                indexingInProgress.set(false);
            }
        });
    }

    private void updateIndex(Project project) {
        // 获取项目根目录
        VirtualFile[] contentRoots = ReadAction.compute(() ->
            ProjectRootManager.getInstance(project).getContentRoots()
        );

        Set<String> visitedFiles = new HashSet<>();
        boolean hadError = false;
        for (VirtualFile root : contentRoots) {
            try {
                indexDirectory(project, root, visitedFiles);
            } catch (Exception ignored) {
                hadError = true;
                // 索引失败不影响补全功能
            }
        }
        if (!hadError) {
            removeDeletedFiles(visitedFiles);
        }
    }

    private void indexDirectory(Project project, VirtualFile dir, Set<String> visitedFiles) {
        VfsUtil.visitChildrenRecursively(dir, new VirtualFileVisitor<>() {
            @Override
            public boolean visitFile(VirtualFile file) {
                if (shouldSkip(file)) {
                    return false;
                }

                String path = file.getPath();
                visitedFiles.add(path);
                if (indexedFiles.contains(path) && !IndexPersistence.needsReindex(file, fileModificationTimes)) {
                    return true;
                }

                // 只索引文本文件
                if (!file.isInLocalFileSystem() || file.isDirectory() || !file.isValid()) {
                    return true;
                }

                try {
                    if (indexedFiles.contains(path)) {
                        removeFile(path);
                    }
                    ReadAction.run(() -> {
                        PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
                        if (psiFile != null && psiFile.isPhysical()) {
                            String content = psiFile.getText();
                            if (content != null && !content.isBlank()) {
                                String language = psiFile.getLanguage().getDisplayName();
                                String fileName = file.getName();
                                List<CodeChunk> chunks = CodeChunker.chunk(path, fileName, content, language);
                                for (CodeChunk chunk : chunks) {
                                    index.addDocument(chunk);
                                }
                                indexedFiles.add(path);
                                fileModificationTimes.put(path, file.getModificationStamp());
                            }
                        }
                    });
                } catch (Exception ignored) {
                    // 单个文件索引失败，继续处理其他文件
                }

                return true;
            }
        });
    }

    private void removeDeletedFiles(Set<String> visitedFiles) {
        for (String indexedFile : new ArrayList<>(indexedFiles)) {
            if (!visitedFiles.contains(indexedFile)) {
                removeFile(indexedFile);
            }
        }
    }

    private boolean shouldSkip(VirtualFile file) {
        String name = file.getName();

        // 跳过隐藏文件和目录
        if (name.startsWith(".")) {
            return true;
        }

        // 跳过排除的目录
        if (file.isDirectory()) {
            return EXCLUDED_DIRS.contains(name.toLowerCase());
        }

        // 跳过排除的文件类型
        String extension = file.getExtension();
        if (extension != null && EXCLUDED_EXTENSIONS.contains(extension.toLowerCase())) {
            return true;
        }

        // 跳过过大的文件
        if (file.getLength() > 100 * 1024) { // 100KB
            return true;
        }

        return false;
    }

    /**
     * 清空索引。
     */
    public void clearIndex(Project project) {
        index.clear();
        indexedFiles.clear();
        fileModificationTimes.clear();
        lastIndexTime = 0;
        if (project != null) {
            IndexPersistence.delete(project);
        }
    }

    /**
     * 立即重建索引。
     */
    public void rebuildIndex(Project project) {
        if (project == null || !indexingInProgress.compareAndSet(false, true)) {
            return;
        }

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                clearIndex(project);
                updateIndex(project);
                lastIndexTime = System.currentTimeMillis();
                IndexPersistence.save(project, index, fileModificationTimes);
            } finally {
                indexingInProgress.set(false);
            }
        });
    }

    /**
     * 移除指定文件的索引。
     */
    public void removeFile(String filePath) {
        index.removeFile(filePath);
        indexedFiles.remove(filePath);
        fileModificationTimes.remove(filePath);
    }

    /**
     * 获取索引统计信息。
     */
    public IndexStats getStats() {
        return new IndexStats(
            indexedFiles.size(),
            index.getDocumentCount(),
            lastIndexTime,
            index.estimatedMemoryBytes(),
            indexingInProgress.get()
        );
    }
}
