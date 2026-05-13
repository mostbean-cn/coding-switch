package com.github.mostbean.codingswitch.service;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.application.ApplicationManager;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Service(Service.Level.APP)
public final class AiCompletionCache {

    private static final int MAX_CACHE_SIZE = 100;
    private static final long CACHE_EXPIRY_MS = 5 * 60 * 1000; // 5 minutes

    private final Map<String, CacheEntry> cache = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
            return size() > MAX_CACHE_SIZE;
        }
    };

    public static AiCompletionCache getInstance() {
        return ApplicationManager.getApplication().getService(AiCompletionCache.class);
    }

    public synchronized Optional<String> get(String filePath, int offset, long documentStamp) {
        String key = buildKey(filePath, offset, documentStamp);
        CacheEntry entry = cache.get(key);
        if (entry != null && !entry.isExpired()) {
            return Optional.of(entry.completion());
        }
        if (entry != null) {
            cache.remove(key);
        }
        return Optional.empty();
    }

    public synchronized void put(String filePath, int offset, long documentStamp, String completion) {
        if (completion == null || completion.isBlank()) {
            return;
        }
        String key = buildKey(filePath, offset, documentStamp);
        cache.put(key, new CacheEntry(completion, System.currentTimeMillis()));
    }

    public synchronized void invalidate(String filePath) {
        cache.entrySet().removeIf(entry -> entry.getKey().startsWith(filePath + ":"));
    }

    public synchronized void clear() {
        cache.clear();
    }

    private String buildKey(String filePath, int offset, long documentStamp) {
        return filePath + ":" + offset + ":" + documentStamp;
    }

    private record CacheEntry(String completion, long createdAt) {
        boolean isExpired() {
            return System.currentTimeMillis() - createdAt > CACHE_EXPIRY_MS;
        }
    }
}
