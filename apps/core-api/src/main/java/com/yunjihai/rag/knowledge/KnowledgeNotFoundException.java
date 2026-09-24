package com.yunjihai.rag.knowledge;

public final class KnowledgeNotFoundException extends RuntimeException {

    public KnowledgeNotFoundException(String message) {
        super(message);
    }
}
