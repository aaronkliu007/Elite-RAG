package com.yunjihai.rag.solution.generation;

public final class GenerationStateException extends RuntimeException {

    public GenerationStateException() {
        super("GENERATION_STATE_INVALID");
    }
}
