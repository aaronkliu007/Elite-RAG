package com.yunjihai.rag.retrieval.embedding;

public final class EmbeddingUnavailableException extends RuntimeException {
    public EmbeddingUnavailableException(String code) {
        super(validate(code), null, false, true);
    }

    public EmbeddingUnavailableException(String code, Throwable cause) {
        super(validate(code), cause, false, true);
    }

    private static String validate(String code) {
        if (code == null || code.isBlank() || code.length() > 100) {
            throw new IllegalArgumentException("embedding error code is invalid");
        }
        return code;
    }
}
