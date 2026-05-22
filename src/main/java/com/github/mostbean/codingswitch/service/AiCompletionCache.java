package com.github.mostbean.codingswitch.service;

import com.github.mostbean.codingswitch.model.AiCompletionRequest;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.application.ApplicationManager;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Service(Service.Level.APP)
public final class AiCompletionCache {

    private static final int MAX_CACHE_SIZE = 100;
    private static final int MAX_CONTEXT_CACHE_SIZE = 80;
    private static final int MAX_NEGATIVE_CACHE_SIZE = 80;
    private static final int PREFIX_HASH_CHARS = 1200;
    private static final int SUFFIX_HASH_CHARS = 500;
    private static final long CACHE_EXPIRY_MS = 5 * 60 * 1000; // 5 minutes
    private static final long NEGATIVE_CACHE_EXPIRY_MS = 20 * 1000;

    private final Map<String, CacheEntry> cache = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
            return size() > MAX_CACHE_SIZE;
        }
    };
    private final Map<String, CacheEntry> contextCache = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
            return size() > MAX_CONTEXT_CACHE_SIZE;
        }
    };
    private final Map<String, NegativeCacheEntry> negativeCache = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, NegativeCacheEntry> eldest) {
            return size() > MAX_NEGATIVE_CACHE_SIZE;
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

    public synchronized Optional<String> getContext(AiCompletionRequest request) {
        String key = buildContextKey(request);
        CacheEntry entry = contextCache.get(key);
        if (entry != null && !entry.isExpired()) {
            return Optional.of(entry.completion());
        }
        if (entry != null) {
            contextCache.remove(key);
        }
        return Optional.empty();
    }

    public synchronized void putContext(AiCompletionRequest request, String completion) {
        if (completion == null || completion.isBlank()) {
            return;
        }
        contextCache.put(buildContextKey(request), new CacheEntry(completion, System.currentTimeMillis()));
        negativeCache.remove(buildContextKey(request));
    }

    public synchronized boolean isNegativeCached(AiCompletionRequest request) {
        String key = buildContextKey(request);
        NegativeCacheEntry entry = negativeCache.get(key);
        if (entry != null && !entry.isExpired()) {
            return true;
        }
        if (entry != null) {
            negativeCache.remove(key);
        }
        return false;
    }

    public synchronized void putNegative(AiCompletionRequest request) {
        negativeCache.put(buildContextKey(request), new NegativeCacheEntry(System.currentTimeMillis()));
    }

    public synchronized void invalidate(String filePath) {
        cache.entrySet().removeIf(entry -> entry.getKey().startsWith(filePath + ":"));
    }

    public synchronized void clear() {
        cache.clear();
        contextCache.clear();
        negativeCache.clear();
    }

    private String buildKey(String filePath, int offset, long documentStamp) {
        return filePath + ":" + offset + ":" + documentStamp;
    }

    private String buildContextKey(AiCompletionRequest request) {
        return request.profile().getFormat().name()
            + ":"
            + request.profile().getModel()
            + ":"
            + request.lengthLevel().name()
            + ":"
            + hash(tail(request.fimPrefix(), PREFIX_HASH_CHARS))
            + ":"
            + hash(head(request.fimSuffix(), SUFFIX_HASH_CHARS));
    }

    private String head(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value == null ? "" : value;
        }
        return value.substring(0, maxChars);
    }

    private String tail(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value == null ? "" : value;
        }
        return value.substring(value.length() - maxChars);
    }

    private String hash(String value) {
        return Integer.toHexString(value.hashCode());
    }

    private record CacheEntry(String completion, long createdAt) {
        boolean isExpired() {
            return System.currentTimeMillis() - createdAt > CACHE_EXPIRY_MS;
        }
    }

    private record NegativeCacheEntry(long createdAt) {
        boolean isExpired() {
            return System.currentTimeMillis() - createdAt > NEGATIVE_CACHE_EXPIRY_MS;
        }
    }
}
