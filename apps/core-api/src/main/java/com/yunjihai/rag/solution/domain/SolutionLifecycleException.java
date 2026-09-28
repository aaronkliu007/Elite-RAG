package com.yunjihai.rag.solution.domain;

import java.util.Objects;

public class SolutionLifecycleException extends RuntimeException {

    private final String errorCode;

    public SolutionLifecycleException(String errorCode) {
        super(Objects.requireNonNull(errorCode, "errorCode"));
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
