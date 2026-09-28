package com.yunjihai.rag.solution.generation;

public final class GenerationRequestConflictException extends RuntimeException {

    public GenerationRequestConflictException() {
        super("GENERATION_REQUEST_CONFLICT");
    }
}
