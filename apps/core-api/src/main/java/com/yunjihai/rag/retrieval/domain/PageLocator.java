package com.yunjihai.rag.retrieval.domain;

public record PageLocator(int pageNumber, NormalizedBoundingBox boundingBox)
        implements SourceLocator {

    public PageLocator {
        if (pageNumber < 1) {
            throw new IllegalArgumentException("page number must start at one");
        }
    }
}
