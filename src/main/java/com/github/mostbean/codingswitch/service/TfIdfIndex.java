package com.github.mostbean.codingswitch.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 轻量级 TF-IDF 索引，用于代码检索。
 */
public final class TfIdfIndex {

    private static final Pattern TOKEN_PATTERN = Pattern.compile("[a-zA-Z_]\\w{2,}");
    private static final Pattern STOP_WORDS = Pattern.compile(
        "^(?:the|and|for|are|but|not|you|all|can|has|her|was|one|our|out|this|that|with|have|from|they|been|said|each|which|their|will|other|about|many|then|them|would|like|just|into|also|some|could|than|more|very|when|come|made|after|did|back|only|well|just|over|such|take|year|your|good|give|most|such|know|get|may|old|see|him|two|how|its|our|way|who|boy|did|let|put|say|she|too|use)$"
    );

    // term -> (docId -> tf)
    private final Map<String, Map<String, Integer>> termFrequency = new ConcurrentHashMap<>();
    // docId -> chunk
    private final Map<String, CodeChunk> documents = new ConcurrentHashMap<>();
    // docId -> term count
    private final Map<String, Integer> docTermCounts = new ConcurrentHashMap<>();
    // 文档总数
    private volatile int totalDocuments = 0;

    public TfIdfIndex() {
    }

    /**
     * 添加或更新文档到索引。
     */
    public void addDocument(CodeChunk chunk) {
        String docId = chunk.getId();

        // 如果已存在，先移除
        if (documents.containsKey(docId)) {
            removeDocument(docId);
        }

        documents.put(docId, chunk);
        List<String> tokens = tokenize(chunk.content());
        docTermCounts.put(docId, tokens.size());

        Map<String, Integer> docTf = new HashMap<>();
        for (String token : tokens) {
            docTf.merge(token, 1, Integer::sum);
        }

        for (Map.Entry<String, Integer> entry : docTf.entrySet()) {
            termFrequency
                .computeIfAbsent(entry.getKey(), k -> new ConcurrentHashMap<>())
                .put(docId, entry.getValue());
        }

        totalDocuments = documents.size();
    }

    /**
     * 移除文档。
     */
    public void removeDocument(String docId) {
        CodeChunk chunk = documents.remove(docId);
        if (chunk == null) {
            return;
        }

        List<String> tokens = tokenize(chunk.content());
        for (String token : new HashSet<>(tokens)) {
            Map<String, Integer> docMap = termFrequency.get(token);
            if (docMap != null) {
                docMap.remove(docId);
                if (docMap.isEmpty()) {
                    termFrequency.remove(token);
                }
            }
        }

        docTermCounts.remove(docId);
        totalDocuments = documents.size();
    }

    /**
     * 移除指定文件的所有文档。
     */
    public void removeFile(String filePath) {
        List<String> toRemove = new ArrayList<>();
        for (CodeChunk chunk : documents.values()) {
            if (chunk.filePath().equals(filePath)) {
                toRemove.add(chunk.getId());
            }
        }
        toRemove.forEach(this::removeDocument);
    }

    /**
     * 搜索相关代码块。
     *
     * @param query 查询文本
     * @param limit 返回结果数量
     * @return 按相关性排序的代码块列表
     */
    public List<SearchResult> search(String query, int limit) {
        if (query == null || query.isBlank() || documents.isEmpty()) {
            return List.of();
        }

        List<String> queryTokens = tokenize(query);
        if (queryTokens.isEmpty()) {
            return List.of();
        }

        // 计算每个文档的 TF-IDF 分数
        Map<String, Double> scores = new HashMap<>();
        for (String docId : documents.keySet()) {
            double score = calculateScore(docId, queryTokens);
            if (score > 0) {
                scores.put(docId, score);
            }
        }

        // 排序并返回 Top-N
        return scores.entrySet().stream()
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
            .limit(limit)
            .map(entry -> new SearchResult(documents.get(entry.getKey()), entry.getValue()))
            .toList();
    }

    /**
     * 获取索引中的文档数量。
     */
    public int getDocumentCount() {
        return documents.size();
    }

    /**
     * 清空索引。
     */
    public void clear() {
        termFrequency.clear();
        documents.clear();
        docTermCounts.clear();
        totalDocuments = 0;
    }

    /**
     * 估算索引占用的内存字节数。
     */
    public long estimatedMemoryBytes() {
        long bytes = 0;

        // 估算 documents map
        for (CodeChunk chunk : documents.values()) {
            bytes += chunk.filePath().length() * 2L;
            bytes += chunk.fileName().length() * 2L;
            bytes += chunk.content().length() * 2L;
            bytes += chunk.language().length() * 2L;
            bytes += 64; // 对象开销
        }

        // 估算 termFrequency map
        for (Map.Entry<String, Map<String, Integer>> entry : termFrequency.entrySet()) {
            bytes += entry.getKey().length() * 2L;
            bytes += entry.getValue().size() * 16L; // 每个 entry 约 16 字节
        }

        // 估算 docTermCounts map
        bytes += docTermCounts.size() * 16L;

        return bytes;
    }

    private double calculateScore(String docId, List<String> queryTokens) {
        double score = 0;
        int docTermCount = docTermCounts.getOrDefault(docId, 1);

        for (String token : queryTokens) {
            Map<String, Integer> docMap = termFrequency.get(token);
            if (docMap == null) {
                continue;
            }

            Integer tf = docMap.get(docId);
            if (tf == null) {
                continue;
            }

            // TF: 词频 / 文档总词数
            double normalizedTf = (double) tf / docTermCount;

            // 平滑 IDF，避免小项目或常见符号得到非正分数而被整体过滤。
            int docsWithTerm = docMap.size();
            double idf = Math.log((1.0 + totalDocuments) / (1.0 + docsWithTerm)) + 1.0;

            score += normalizedTf * idf;
        }

        return score;
    }

    private static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return tokens;
        }

        var matcher = TOKEN_PATTERN.matcher(text.toLowerCase());
        while (matcher.find()) {
            String token = matcher.group();
            if (!STOP_WORDS.matcher(token).matches() && token.length() >= 3) {
                tokens.add(token);
            }
        }

        return tokens;
    }

    /**
     * 搜索结果。
     */
    public record SearchResult(CodeChunk chunk, double score) {
    }
}
