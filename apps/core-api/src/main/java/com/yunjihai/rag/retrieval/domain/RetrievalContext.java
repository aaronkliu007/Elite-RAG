package com.yunjihai.rag.retrieval.domain;

import com.yunjihai.rag.access.PrincipalRef;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

public record RetrievalContext(Instant startedAt, Set<PrincipalRef> effectivePrincipals) {

    public RetrievalContext {
        Objects.requireNonNull(startedAt, "startedAt must not be null");
        effectivePrincipals = Set.copyOf(
                Objects.requireNonNull(effectivePrincipals, "effectivePrincipals must not be null"));
    }
}
