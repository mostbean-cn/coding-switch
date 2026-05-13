package com.github.mostbean.codingswitch.service;

/**
 * 代码块，用于 TF-IDF 索引的基本单位。
 */
public record CodeChunk(
    String filePath,
    String fileName,
    int startLine,
    int endLine,
    String content,
    String language
) {
    public String getId() {
        return filePath + ":" + startLine + "-" + endLine;
    }

    public int getLength() {
        return content.length();
    }
}
