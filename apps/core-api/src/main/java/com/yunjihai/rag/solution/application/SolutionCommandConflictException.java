package com.yunjihai.rag.solution.application;

public final class SolutionCommandConflictException extends RuntimeException {
    private final String code;
    public SolutionCommandConflictException(String code) {
        super(code, null, false, true);
        this.code = code;
    }
    public String code() { return code; }
}
