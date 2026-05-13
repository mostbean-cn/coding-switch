package com.github.mostbean.codingswitch.service;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 索引持久化，支持序列化到磁盘和从磁盘加载。
 */
public final class IndexPersistence {

    private static final String INDEX_FILE_NAME = "codingSwitchIndex.ser";

    private IndexPersistence() {
    }

    /**
     * 保存索引到磁盘。
     */
    public static void save(Project project, TfIdfIndex index, Map<String, Long> fileModificationTimes) {
        Path indexPath = getIndexPath(project);
        if (indexPath == null) {
            return;
        }

        try {
            Files.createDirectories(indexPath.getParent());
            IndexData data = new IndexData(
                index.getDocumentCount(),
                fileModificationTimes,
                index.snapshot(),
                System.currentTimeMillis()
            );

            try (ObjectOutputStream oos = new ObjectOutputStream(Files.newOutputStream(indexPath))) {
                oos.writeObject(data);
            }
        } catch (IOException e) {
            // 保存失败不影响补全功能
        }
    }

    /**
     * 从磁盘加载索引元数据。
     */
    public static IndexData load(Project project) {
        Path indexPath = getIndexPath(project);
        if (indexPath == null || !Files.exists(indexPath)) {
            return null;
        }

        try (ObjectInputStream ois = new ObjectInputStream(Files.newInputStream(indexPath))) {
            return (IndexData) ois.readObject();
        } catch (IOException | ClassNotFoundException e) {
            // 加载失败时返回 null，会触发重新索引
            return null;
        }
    }

    /**
     * 删除索引文件。
     */
    public static void delete(Project project) {
        Path indexPath = getIndexPath(project);
        if (indexPath != null && Files.exists(indexPath)) {
            try {
                Files.delete(indexPath);
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * 检查文件是否需要重新索引。
     */
    public static boolean needsReindex(VirtualFile file, Map<String, Long> cachedModificationTimes) {
        String path = file.getPath();
        Long cachedTime = cachedModificationTimes.get(path);
        if (cachedTime == null) {
            return true;
        }
        return file.getModificationStamp() != cachedTime;
    }

    private static Path getIndexPath(Project project) {
        VirtualFile baseDir = project.getBaseDir();
        if (baseDir == null) {
            return null;
        }
        return Path.of(baseDir.getPath(), ".idea", INDEX_FILE_NAME);
    }

    /**
     * 索引元数据。
     */
    public static class IndexData implements Serializable {
        private static final long serialVersionUID = 1L;

        private final int documentCount;
        private final Map<String, Long> fileModificationTimes;
        private final TfIdfIndex.IndexSnapshot indexSnapshot;
        private final long saveTime;

        public IndexData(int documentCount, Map<String, Long> fileModificationTimes, long saveTime) {
            this(documentCount, fileModificationTimes, null, saveTime);
        }

        public IndexData(
            int documentCount,
            Map<String, Long> fileModificationTimes,
            TfIdfIndex.IndexSnapshot indexSnapshot,
            long saveTime
        ) {
            this.documentCount = documentCount;
            this.fileModificationTimes = new ConcurrentHashMap<>(fileModificationTimes);
            this.indexSnapshot = indexSnapshot;
            this.saveTime = saveTime;
        }

        public int getDocumentCount() {
            return documentCount;
        }

        public Map<String, Long> getFileModificationTimes() {
            return fileModificationTimes;
        }

        public TfIdfIndex.IndexSnapshot getIndexSnapshot() {
            return indexSnapshot;
        }

        public long getSaveTime() {
            return saveTime;
        }
    }
}
