package com.yunjihai.rag.solution.domain;

import com.yunjihai.rag.knowledge.DocumentVersionRef;
import com.yunjihai.rag.retrieval.domain.SourceLocator;

import java.util.Objects;

public record RequirementSource(DocumentVersionRef documentVersion, SourceLocator locator) {

    public RequirementSource {
        Objects.requireNonNull(documentVersion, "documentVersion");
        Objects.requireNonNull(locator, "locator");
    }
}
