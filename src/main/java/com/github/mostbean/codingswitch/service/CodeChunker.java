package com.github.mostbean.codingswitch.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 代码分块器，将文件按函数/类级别分块。
 */
public final class CodeChunker {

    private static final int MIN_CHUNK_LINES = 5;
    private static final int MAX_CHUNK_LINES = 100;
    private static final int MAX_CHUNK_CHARS = 2000;

    // 匹配函数/方法/类定义的起始行
    private static final Pattern BLOCK_START_PATTERN = Pattern.compile(
        "^\\s*(?:" +
            "(?:public|private|protected|static|final|abstract|synchronized|native)\\s+" +
            ".*\\{\\s*$" +
            "|" +
            "(?:class|interface|enum|record)\\s+\\w+.*\\{\\s*$" +
            "|" +
            "(?:def|function|func|fn)\\s+\\w+.*" +
            "|" +
            "(?:async\\s+)?(?:function|=>)\\s*\\(.*\\)\\s*(?:=>|\\{)\\s*$" +
        ")",
        Pattern.MULTILINE
    );

    private CodeChunker() {
    }

    /**
     * 将文件内容分块。
     *
     * @param filePath 文件路径
     * @param fileName 文件名
     * @param content  文件内容
     * @param language 语言标识
     * @return 分块后的代码块列表
     */
    public static List<CodeChunk> chunk(String filePath, String fileName, String content, String language) {
        List<CodeChunk> chunks = new ArrayList<>();
        if (content == null || content.isBlank()) {
            return chunks;
        }

        String[] lines = content.split("\\n", -1);
        if (lines.length <= MAX_CHUNK_LINES) {
            // 文件较小，整体作为一个块
            chunks.add(new CodeChunk(filePath, fileName, 1, lines.length, content.trim(), language));
            return chunks;
        }

        // 按函数/类定义分块
        List<int[]> blockRanges = findBlockRanges(lines);
        if (blockRanges.isEmpty()) {
            // 没有找到明显的块边界，按固定大小分块
            blockRanges = splitByFixedSize(lines.length, MAX_CHUNK_LINES);
        }

        for (int[] range : blockRanges) {
            int start = range[0];
            int end = Math.min(range[1], lines.length);

            if (end - start < MIN_CHUNK_LINES) {
                continue;
            }

            StringBuilder sb = new StringBuilder();
            for (int i = start; i < end; i++) {
                sb.append(lines[i]).append("\n");
                if (sb.length() > MAX_CHUNK_CHARS) {
                    break;
                }
            }

            String chunkContent = sb.toString().trim();
            if (!chunkContent.isBlank()) {
                chunks.add(new CodeChunk(filePath, fileName, start + 1, end, chunkContent, language));
            }
        }

        return chunks;
    }

    private static List<int[]> findBlockRanges(String[] lines) {
        List<int[]> ranges = new ArrayList<>();
        List<Integer> starts = new ArrayList<>();

        for (int i = 0; i < lines.length; i++) {
            Matcher matcher = BLOCK_START_PATTERN.matcher(lines[i]);
            if (matcher.find()) {
                starts.add(i);
            }
        }

        if (starts.isEmpty()) {
            return ranges;
        }

        // 根据块起始位置划分范围
        for (int i = 0; i < starts.size(); i++) {
            int start = starts.get(i);
            int end = (i + 1 < starts.size()) ? starts.get(i + 1) : lines.length;

            // 限制块大小
            if (end - start > MAX_CHUNK_LINES) {
                end = start + MAX_CHUNK_LINES;
            }

            ranges.add(new int[]{start, end});
        }

        return ranges;
    }

    private static List<int[]> splitByFixedSize(int totalLines, int chunkSize) {
        List<int[]> ranges = new ArrayList<>();
        for (int i = 0; i < totalLines; i += chunkSize) {
            ranges.add(new int[]{i, Math.min(i + chunkSize, totalLines)});
        }
        return ranges;
    }
}
