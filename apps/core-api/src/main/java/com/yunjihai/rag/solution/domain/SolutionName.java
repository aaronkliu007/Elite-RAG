package com.yunjihai.rag.solution.domain;

public record SolutionName(String value) {

    public SolutionName {
        value = SolutionText.required(value, "name");
        if (value.codePointCount(0, value.length()) > 200) {
            throw new IllegalArgumentException("name is too long");
        }
    }

    public static SolutionName of(String value) {
        return new SolutionName(value);
    }
}
