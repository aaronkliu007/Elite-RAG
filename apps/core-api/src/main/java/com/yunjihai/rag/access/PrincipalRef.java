package com.yunjihai.rag.access;

import java.util.Objects;
import java.util.UUID;

public record PrincipalRef(PrincipalType type, UUID id) {

    public PrincipalRef {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(id, "id must not be null");
    }
}
