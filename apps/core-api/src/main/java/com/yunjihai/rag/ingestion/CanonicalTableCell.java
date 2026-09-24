package com.yunjihai.rag.ingestion;

public record CanonicalTableCell(int row, int column, int rowSpan, int columnSpan, String text) {
}
