package com.yunjihai.rag.retrieval.domain;

public sealed interface SourceLocator
        permits PageLocator, SlideLocator, TableLocator, MarkdownLocator, SectionLocator {
}
