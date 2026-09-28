package com.yunjihai.rag.solution.model;

public interface SolutionModelPort {

    <T> SolutionModelResponse<T> execute(SolutionModelRequest request, Class<T> responseType);
}
