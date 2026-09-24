package com.yunjihai.rag.ingestion;

public record ParserError(ParserErrorCode code, String message, boolean retryable) {
}
