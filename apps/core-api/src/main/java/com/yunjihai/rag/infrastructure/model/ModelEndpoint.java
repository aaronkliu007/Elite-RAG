package com.yunjihai.rag.infrastructure.model;

public enum ModelEndpoint {
    EMBEDDINGS("/v1/embeddings", "embeddings"),
    RERANK("/v1/rerank", "rerank"),
    CHAT_COMPLETIONS("/v1/chat/completions", "chat-completions");

    private final String path;
    private final String identifier;

    ModelEndpoint(String path, String identifier) {
        this.path = path;
        this.identifier = identifier;
    }

    public String path() {
        return path;
    }

    public String identifier() {
        return identifier;
    }
}
