package com.yunjihai.rag.solution.model;

import java.util.Objects;

public record PromptRef(String name, String version) {

    public PromptRef {
        name = requiredToken(name, "name");
        version = requiredToken(version, "version");
    }

    private static String requiredToken(String value, String field) {
        Objects.requireNonNull(value, field);
        var normalized = value.strip();
        if (!normalized.matches("[a-z][a-z0-9-]{0,63}")) {
            throw new IllegalArgumentException("invalid prompt " + field);
        }
        return normalized;
    }
}
