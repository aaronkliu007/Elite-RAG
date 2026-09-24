package com.yunjihai.rag.ingestion;

public final class InvalidParserResponseException extends RuntimeException {

    public InvalidParserResponseException(String message) {
        super(message);
    }

    public InvalidParserResponseException(String message, Throwable cause) {
        super(message, cause);
    }
}
