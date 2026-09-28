package com.yunjihai.rag.solution.model;

import com.yunjihai.rag.solution.generation.ModelUsage;

import java.util.Objects;

public record SolutionModelResponse<T>(
        Class<T> bodyType,
        T body,
        String modelId,
        ModelUsage usage) {

    public SolutionModelResponse {
        Objects.requireNonNull(bodyType, "bodyType");
        Objects.requireNonNull(body, "body");
        if (!bodyType.isInstance(body)) {
            throw new IllegalArgumentException("model response body type mismatch");
        }
        Objects.requireNonNull(modelId, "modelId");
        modelId = modelId.strip();
        if (modelId.isEmpty() || modelId.length() > 128) {
            throw new IllegalArgumentException("invalid model id");
        }
        Objects.requireNonNull(usage, "usage");
    }

    @Override
    public String toString() {
        return "SolutionModelResponse[bodyType=" + bodyType.getSimpleName()
                + ", modelId=" + modelId + ", usage=" + usage + "]";
    }
}
