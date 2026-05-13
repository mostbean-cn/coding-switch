package com.github.mostbean.codingswitch.service;

/**
 * 索引统计信息。
 */
public record IndexStats(
    int filesIndexed,
    int chunksIndexed,
    long lastUpdateTime,
    long estimatedMemoryBytes,
    boolean isIndexing
) {
}
