package com.yunjihai.rag.retrieval.domain;

import java.util.Objects;

public record TableLocator(
        String tableName,
        int startRow,
        int endRow,
        int startColumn,
        int endColumn) implements SourceLocator {

    public TableLocator {
        Objects.requireNonNull(tableName, "tableName must not be null");
        tableName = tableName.strip();
        if (tableName.isEmpty()) {
            throw new IllegalArgumentException("table name must not be blank");
        }
        if (startRow < 1 || endRow < startRow || startColumn < 1 || endColumn < startColumn) {
            throw new IllegalArgumentException("table range is invalid");
        }
    }
}
