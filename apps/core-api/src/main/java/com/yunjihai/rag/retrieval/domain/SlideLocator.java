package com.yunjihai.rag.retrieval.domain;

public record SlideLocator(int slideNumber) implements SourceLocator {

    public SlideLocator {
        if (slideNumber < 1) {
            throw new IllegalArgumentException("slide number must start at one");
        }
    }
}
