package com.yunjihai.rag.solution.domain;

import java.text.Normalizer;

final class SolutionText {

    private SolutionText() {
    }

    static String required(String value, String field) {
        if (value == null) {
            throw new NullPointerException(field);
        }
        var normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replaceAll("\\s+", " ")
                .strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
