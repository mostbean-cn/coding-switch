package com.github.mostbean.codingswitch.service;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 上下文收集器管理器，协调多个收集器收集上下文信息。
 */
public final class ContextCollectorManager {

    private static final int MAX_CONTEXT_CHARS = 2000;
    private static volatile ContextCollectorManager instance;

    private final Map<String, CollectorGroup> projectCollectors = new ConcurrentHashMap<>();
    private final List<ContextCollector> globalCollectors = new ArrayList<>();

    public static ContextCollectorManager getInstance() {
        if (instance == null) {
            synchronized (ContextCollectorManager.class) {
                if (instance == null) {
                    instance = new ContextCollectorManager();
                }
            }
        }
        return instance;
    }

    private ContextCollectorManager() {
    }

    /**
     * 收集所有上下文信息。
     *
     * @param project      当前项目
     * @param editor       当前编辑器
     * @param cursorOffset 光标位置
     * @return 收集到的上下文信息
     */
    public String collectAll(Project project, Editor editor, int cursorOffset) {
        if (!AiFeatureSettings.getInstance().isProjectContextEnabled()) {
            return "";
        }

        List<String> contexts = new ArrayList<>();
        CollectorGroup group = collectorGroup(project);

        for (ContextCollector collector : group.collectors()) {
            try {
                String context = collector.collect(project, editor, cursorOffset);
                if (context != null && !context.isBlank()) {
                    contexts.add(context);
                }
            } catch (Exception ignored) {
                // 收集器失败不应影响补全功能
            }
        }

        if (contexts.isEmpty()) {
            return "";
        }

        String combined = String.join("\n\n", contexts);
        if (combined.length() > MAX_CONTEXT_CHARS) {
            combined = combined.substring(0, MAX_CONTEXT_CHARS) + "...";
        }

        return combined;
    }

    /**
     * 注册新的上下文收集器。
     *
     * @param collector 要注册的收集器
     */
    public void registerCollector(ContextCollector collector) {
        if (collector != null) {
            synchronized (globalCollectors) {
                globalCollectors.add(collector);
            }
            projectCollectors.clear();
        }
    }

    /**
     * 清空所有打开项目的代码库索引。
     */
    public void clearAllCodebaseIndexes() {
        for (Project project : ProjectManager.getInstance().getOpenProjects()) {
            collectorGroup(project).codebaseSearchCollector().clearIndex(project);
        }
    }

    /**
     * 立即重建所有打开项目的代码库索引。
     */
    public void rebuildAllCodebaseIndexes() {
        for (Project project : ProjectManager.getInstance().getOpenProjects()) {
            collectorGroup(project).codebaseSearchCollector().rebuildIndex(project);
        }
    }

    /**
     * 汇总所有打开项目的代码库索引统计。
     */
    public IndexStats getAggregateCodebaseStats() {
        int filesIndexed = 0;
        int chunksIndexed = 0;
        long lastUpdateTime = 0;
        long estimatedMemoryBytes = 0;
        boolean indexing = false;

        for (Project project : ProjectManager.getInstance().getOpenProjects()) {
            IndexStats stats = collectorGroup(project).codebaseSearchCollector().getStats();
            filesIndexed += stats.filesIndexed();
            chunksIndexed += stats.chunksIndexed();
            lastUpdateTime = Math.max(lastUpdateTime, stats.lastUpdateTime());
            estimatedMemoryBytes += stats.estimatedMemoryBytes();
            indexing = indexing || stats.isIndexing();
        }

        return new IndexStats(filesIndexed, chunksIndexed, lastUpdateTime, estimatedMemoryBytes, indexing);
    }

    private CollectorGroup collectorGroup(Project project) {
        String key = projectKey(project);
        return projectCollectors.computeIfAbsent(key, ignored -> createCollectorGroup());
    }

    private CollectorGroup createCollectorGroup() {
        CodebaseSearchCollector codebaseSearchCollector = new CodebaseSearchCollector();
        List<ContextCollector> collectors = new ArrayList<>();
        collectors.add(codebaseSearchCollector);
        collectors.add(new RecentEditedFileCollector());
        collectors.add(new ImportedSymbolCollector());
        synchronized (globalCollectors) {
            collectors.addAll(globalCollectors);
        }
        collectors.sort(Comparator.comparingInt(ContextCollector::getPriority));
        return new CollectorGroup(codebaseSearchCollector, List.copyOf(collectors));
    }

    private String projectKey(Project project) {
        if (project == null) {
            return "";
        }
        String locationHash = project.getLocationHash();
        return locationHash == null || locationHash.isBlank() ? project.getName() : locationHash;
    }

    private record CollectorGroup(
        CodebaseSearchCollector codebaseSearchCollector,
        List<ContextCollector> collectors
    ) {
    }
}
