package com.yunjihai.rag.solution.domain;

public final class ConcurrentSolutionUpdateException extends SolutionLifecycleException {

    public ConcurrentSolutionUpdateException() {
        super("SOLUTION_CONCURRENT_UPDATE");
    }
}
