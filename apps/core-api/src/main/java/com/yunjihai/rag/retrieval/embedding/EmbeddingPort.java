package com.yunjihai.rag.retrieval.embedding;

@FunctionalInterface
public interface EmbeddingPort {
    EmbeddingVector embed(String text);
}
