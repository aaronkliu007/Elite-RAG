package com.yunjihai.rag.knowledge;

import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

public record DocumentMetadata(String classification, List<String> tags) {

    public DocumentMetadata {
        Objects.requireNonNull(tags, "文档标签不能为空");
        if (classification != null) {
            classification = classification.strip();
            if (classification.isEmpty() || codePoints(classification) > 100) {
                throw new IllegalArgumentException("文档分类必须为 1 至 100 个字符");
            }
        }
        var normalized = new TreeSet<String>();
        for (String tag : tags) {
            if (tag == null) {
                throw new IllegalArgumentException("文档标签不能为空");
            }
            String value = tag.strip();
            if (value.isEmpty() || codePoints(value) > 50) {
                throw new IllegalArgumentException("单个文档标签必须为 1 至 50 个字符");
            }
            normalized.add(value);
        }
        if (normalized.size() > 20) {
            throw new IllegalArgumentException("文档标签最多 20 项");
        }
        tags = List.copyOf(normalized);
    }

    public static DocumentMetadata empty() {
        return new DocumentMetadata(null, List.of());
    }

    public boolean complete() {
        return classification != null;
    }

    private static int codePoints(String value) {
        return value.codePointCount(0, value.length());
    }
}
